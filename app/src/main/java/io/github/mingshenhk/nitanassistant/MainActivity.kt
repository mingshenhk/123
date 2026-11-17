package io.github.mingshenhk.nitanassistant

import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private companion object {
        const val IMPORT_MODEL = 1
        const val GOAL_VIEW = 0x10001
    }
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
    private lateinit var modelStatus: TextView
    private lateinit var importModel: Button
    private lateinit var goal: EditText
    private lateinit var aiStart: Button
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
        body.label("任务控制", bold = true, top = 18)
        status = body.label("待命", top = 7)
        operation = body.label("最近操作  —", 14f)
        error = body.label("最近错误  —", 14f)
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
        body.label("本地 AI", bold = true, top = 18)
        body.label(ModelStore.TITLE, 14f)
        modelStatus = body.label("正在检查模型", 14f)
        val modelActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            body.addView(this)
        }
        modelActions.action("模型下载页") {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.PAGE)), "没有可用的浏览器")
        }
        importModel = modelActions.action("导入模型") {
            pickModel()
        }
        goal = EditText(this).apply {
            id = GOAL_VIEW
            hint = "例如 打开日常任务列表"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(MAX_GOAL))
            body.addView(this)
        }
        aiStart = body.action("运行 AI 任务") {
            val instruction = goal.text.toString().trim()
            if (instruction.isEmpty()) {
                goal.error = "请输入任务目标"
            } else {
                GameAccessibilityService.connected?.startAiTask(instruction, ::openGame)
                renderState()
            }
        }
        body.label("规则任务", bold = true, top = 18)
        body.label("${RuleAsset.NAME} · 请先把游戏停在角色入口", 14f)
        start = body.action("运行规则任务") {
            GameAccessibilityService.connected?.startTask(::openGame)
            renderState()
        }
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

    @Suppress("DEPRECATION")
    private fun pickModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, IMPORT_MODEL)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "系统文件选择器不可用", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("This Activity uses the platform document picker result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_MODEL) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            ModelStore.import(this, uri)
        }
        renderState()
    }

    private fun openExternal(intent: Intent, fallback: String) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, fallback, Toast.LENGTH_SHORT).show()
        }
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
        val modelReady = ModelStore.ready(this) != null
        modelStatus.show(when {
            !ModelStore.supported -> "此设备不支持端侧推理"
            ModelStore.importing -> "正在复制并校验模型"
            modelReady -> "模型已就绪 · 离线推理"
            else -> ModelStore.lastError ?: "请选择 ${ModelStore.FILE_NAME}（约 ${ModelStore.SIZE}）"
        })
        importModel.isEnabled = ModelStore.supported && !ModelStore.importing && service?.active != true && !modelReady
        aiStart.isEnabled = start.isEnabled && ModelStore.supported && modelReady && !ModelStore.importing
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
