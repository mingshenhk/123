package io.github.mingshenhk.nitanassistant

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

internal const val SETTINGS_FILE = "assistant-preferences"
internal const val OVERLAY_SETTING = "show-controls"

class GameAccessibilityService : AccessibilityService() {
    companion object {
        private var currentService: WeakReference<GameAccessibilityService>? = null
        internal val connected: GameAccessibilityService? get() = currentService?.get()
        private const val POLL_MILLIS = 400L
        private const val NODE_LIMIT = 2_500
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { runTick() }
    private var overlay: LinearLayout? = null
    private var overlayStatus: TextView? = null
    private var overlayPause: Button? = null
    private var overlayFailed = false
    private var runner: TaskRunner? = null
    internal val phase get() = runner?.phase ?: Phase.IDLE
    internal val status get() = runner?.status ?: "等待启动"
    internal val elapsedMillis get() = runner?.elapsedMillis ?: 0
    internal val active get() = runner?.active == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        runner?.fail("无障碍服务中断")
        updateOverlay(show = false)
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        runner?.fail("无障碍服务已关闭")
        updateOverlay(show = false)
        if (connected === this) currentService = null
        super.onDestroy()
    }

    internal fun startTask(openGame: () -> Unit) {
        if (active) return
        try {
            val task = RuleAsset.load(this)
            val ruleRunner = RuleRunner(task, SystemClock::elapsedRealtime) { level, message ->
                RunLog.append(this, task.name, level, message)
            }
            launch(ruleRunner, openGame)
        } catch (_: Exception) {
            runner?.fail("无法打开游戏或读取任务配置")
            if (phase != Phase.ERROR) RunLog.append(this, RuleAsset.NAME, Level.ERROR, "无法读取任务配置")
        }
    }

    internal fun startAiTask(goal: String, spec: AiModelSpec, openGame: () -> Unit) {
        if (active || !ModelStore.supported || goal.isBlank() || goal.length > MAX_GOAL) return
        val file = ModelStore.ready(this, spec) ?: return
        val model = LocalAiModel(file, cacheDir)
        val ai = AiRunner(goal, model, SystemClock::elapsedRealtime, { level, message ->
            RunLog.append(this, spec.title, level, message)
        }, model::close)
        try {
            launch(ai, openGame)
        } catch (_: RuntimeException) {
            ai.fail("无法打开游戏")
        }
    }

    private fun launch(task: TaskRunner, openGame: () -> Unit) {
        runner = task
        overlayFailed = false
        task.start()
        openGame()
        handler.post(poll)
    }

    internal fun pauseOrResume(openGame: (() -> Unit)? = null) {
        val current = runner ?: return
        when (current.phase) {
            Phase.RUNNING -> current.pause()
            Phase.PAUSED -> {
                val reopen = !isGameInFront()
                if (reopen) {
                    if (openGame == null) return
                    try {
                        openGame()
                    } catch (_: RuntimeException) {
                        current.fail("无法打开游戏")
                        return
                    }
                }
                current.resume(reopeningGame = reopen)
            }
            else -> return
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    internal fun stopTask() {
        runner?.stop()
        handler.removeCallbacks(poll)
        updateOverlay(show = false)
    }

    internal fun isGameInFront(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == GAME_PACKAGE
        } finally {
            release(root)
        }
    }

    private fun runTick() {
        val current = runner ?: return
        if (!current.active) return
        try {
            val inGame = if (current.phase == Phase.RUNNING) {
                readScreen(current::tick)
            } else {
                isGameInFront()
            }
            updateOverlay(show = current.active && inGame)
        } catch (_: RuntimeException) {
            current.fail("读取游戏界面失败")
            updateOverlay(show = false)
        }
        if (current.active) handler.postDelayed(poll, POLL_MILLIS)
    }

    private fun readScreen(consume: (Screen?) -> Unit): Boolean {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != GAME_PACKAGE) {
            root?.let(::release)
            consume(null)
            return false
        }
        val obtained = mutableListOf(root)
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>>()
        pending.addLast(root to null)
        val controls = mutableListOf<Control>()
        val clickGroups = mutableMapOf<AccessibilityNodeInfo, Int>()
        val screenBounds = Rect().also(root::getBoundsInScreen)
        val nodeBounds = Rect()
        try {
            while (pending.isNotEmpty()) {
                val (node, parentClick) = pending.removeLast()
                if (node.packageName?.toString() != GAME_PACKAGE) continue
                val clickNode = when {
                    !node.isEnabled -> null
                    node.isClickable -> node.takeIf { it.isVisibleToUser }
                    else -> parentClick
                }
                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                if (node.isVisibleToUser && (text.isNotEmpty() || description.isNotEmpty())) {
                    node.getBoundsInScreen(nodeBounds)
                    val x = 100L * (nodeBounds.centerX() - screenBounds.left) / screenBounds.width().coerceAtLeast(1)
                    val y = 100L * (nodeBounds.centerY() - screenBounds.top) / screenBounds.height().coerceAtLeast(1)
                    controls += Control(
                        text, description, "$x%横 $y%纵",
                        clickGroup = clickNode?.let { clickGroups.getOrPut(it) { clickGroups.size } },
                        click = clickNode?.let { target -> { clickIfActive(target) } },
                    )
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    obtained += child
                    check(obtained.size <= NODE_LIMIT)
                    pending.addLast(child to clickNode)
                }
            }
            consume(Screen(controls, root.windowId))
            return true
        } finally {
            obtained.forEach(::release)
        }
    }

    private fun clickIfActive(target: AccessibilityNodeInfo): Boolean {
        val activeRoot = rootInActiveWindow ?: return false
        return try {
            activeRoot.packageName?.toString() == GAME_PACKAGE &&
                activeRoot.windowId == target.windowId &&
                target.refresh() && target.isVisibleToUser && target.isEnabled &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            release(activeRoot)
        }
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) = node.recycle()

    private fun updateOverlay(show: Boolean) {
        val enabled = getSharedPreferences(SETTINGS_FILE, MODE_PRIVATE)
            .getBoolean(OVERLAY_SETTING, true)
        val windowManager = getSystemService(WindowManager::class.java)
        if (!show || !enabled) {
            overlay?.let { existing ->
                runCatching { windowManager.removeViewImmediate(existing) }
            }
            overlay = null
            overlayStatus = null
            overlayPause = null
            return
        }
        if (overlayFailed) return
        if (overlay == null) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(4), 0)
                setBackgroundColor(0xf01a2931.toInt())
            }
            overlayStatus = TextView(this).apply {
                setTextColor(0xfff3f6f8.toInt())
                maxWidth = dp(135)
                textSize = 12f
                panel.addView(this)
            }
            overlayPause = Button(this).apply {
                minWidth = 0
                setOnClickListener { pauseOrResume() }
                panel.addView(this)
            }
            panel.addView(Button(this).apply {
                text = "停止"
                minWidth = 0
                setOnClickListener { stopTask() }
            })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                y = dp(80)
            }
            try {
                windowManager.addView(panel, params)
                overlay = panel
            } catch (_: RuntimeException) {
                overlayFailed = true
                overlayStatus = null
                overlayPause = null
                RunLog.append(this, RuleAsset.NAME, Level.WARNING, "悬浮控制不可用，可从助手主页管理任务")
            }
        }
        val paused = phase == Phase.PAUSED
        val label = if (paused) status else phase.label
        if (overlayStatus?.text != label) overlayStatus?.text = label
        val action = if (paused) "继续" else "暂停"
        if (overlayPause?.text != action) overlayPause?.text = action
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
