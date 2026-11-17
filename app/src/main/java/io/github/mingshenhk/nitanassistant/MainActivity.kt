package io.github.mingshenhk.nitanassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, 500)
        }
    }
    private lateinit var game: TextView
    private lateinit var permission: TextView
    private lateinit var status: TextView
    private lateinit var operation: TextView
    private lateinit var error: TextView
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private lateinit var log: TextView
    private var logRevision = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(245, 248, 247))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            scroll.addView(this)
        }
        setContentView(scroll)

        body.label(getString(R.string.app_name), 25f, bold = true)
        body.label("游戏与权限", bold = true, top = 20)
        game = body.label("正在检查游戏")
        permission = body.label("正在检查无障碍服务")
        body.action("打开系统无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        body.label("当前任务", bold = true, top = 18)
        body.label(RuleAsset.NAME)
        body.label("请先把游戏停在角色入口。", 14f)
        status = body.label("待命", top = 7)
        operation = body.label("最近操作  —", 14f)
        error = body.label("最近错误  —", 14f)
        start = body.action("打开游戏并启动") {
            GameAccessibilityService.connected?.startTask(::openGame)
            renderState()
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            body.addView(this)
        }
        pause = actions.action("暂停") {
            GameAccessibilityService.connected?.pauseOrResume(::openGame)
            renderState()
        }
        stop = actions.action("停止") {
            GameAccessibilityService.connected?.stopTask()
            renderState()
        }
        val preferences = getSharedPreferences(SETTINGS_FILE, MODE_PRIVATE)
        body.addView(Switch(this).apply {
            text = "在游戏中显示暂停和停止"
            minHeight = dp(48)
            isChecked = preferences.getBoolean(OVERLAY_SETTING, true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(OVERLAY_SETTING, checked).apply()
            }
        })
        body.label("运行日志", bold = true, top = 18)
        log = body.label("暂无记录", 13f).apply {
            setTextIsSelectable(true)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun openGame() {
        val launch = checkNotNull(packageManager.getLaunchIntentForPackage(GAME_PACKAGE))
        startActivity(launch)
    }

    private fun renderState() {
        val service = GameAccessibilityService.connected
        val installed = packageManager.getLaunchIntentForPackage(GAME_PACKAGE) != null
        game.show(when {
            !installed -> "游戏  未安装"
            service?.isGameInFront() == true -> "游戏  前台"
            else -> "游戏  已安装"
        })
        permission.show(if (service != null) "无障碍  已连接" else "无障碍  未连接")
        val phase = service?.phase ?: Phase.IDLE
        val seconds = (service?.elapsedMillis ?: 0) / 1_000
        status.show(if (service == null) phase.label else "${phase.label} · ${seconds} 秒\n${service.status}")
        start.isEnabled = service != null && installed && !service.active
        pause.isEnabled = service?.active == true
        pause.show(if (phase == Phase.PAUSED) "继续" else "暂停")
        stop.isEnabled = service?.active == true
        val entries = RunLog.read(this)
        if (logRevision != RunLog.revision) {
            operation.text = getString(R.string.latest_operation, RunLog.lastOperation)
            error.text = getString(R.string.latest_error, RunLog.lastError)
            log.text = if (entries.isEmpty()) "暂无记录" else entries.asReversed().joinToString("\n")
            logRevision = RunLog.revision
        }
    }

    private fun TextView.show(value: String) {
        if (text.toString() != value) text = value
    }

    private fun LinearLayout.label(value: String, size: Float = 16f, bold: Boolean = false, top: Int = 0) =
        TextView(context).also { label ->
            label.text = value
            label.textSize = size
            label.setTextColor(Color.rgb(26, 42, 44))
            if (bold) label.setTypeface(null, Typeface.BOLD)
            label.setPadding(0, dp(top), 0, dp(5))
            addView(label)
        }

    private fun LinearLayout.action(title: String, action: () -> Unit) =
        Button(context).also { button ->
            button.text = title
            button.setOnClickListener { action() }
            addView(button)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
