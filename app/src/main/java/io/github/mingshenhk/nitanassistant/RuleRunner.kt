package io.github.mingshenhk.nitanassistant

internal const val GAME_PACKAGE = "com.palmmud.xyjclient"

internal data class Step(
    val text: String,
    val expect: String? = null,
    val timeoutMillis: Long = 15_000,
    val retries: Int = 0,
) {
    init {
        require(text.isNotBlank())
        require(timeoutMillis in 1_000..120_000 && retries in 0..2)
        if (expect != null) {
            require(expect.isNotBlank() && retries == 0)
            require(!isManualControl(text) && !isVerification(expect) && !isRisk(expect))
        }
    }
}

internal data class RuleTask(val name: String, val steps: List<Step>) {
    init {
        require(name.isNotBlank() && steps.size in 1..20)
    }
}

internal enum class Phase(val label: String) {
    IDLE("待命"), RUNNING("运行中"), PAUSED("已暂停"), DONE("已完成"),
    STOPPED("已停止"), ERROR("已停止，需检查"),
}

internal enum class Level { DEBUG, INFO, WARNING, ERROR }

internal data class Control(val text: String, val description: String, val click: (() -> Boolean)?)
internal data class Screen(val controls: List<Control>) {
    fun shows(text: String) = controls.any { it.text == text }
}

private val verificationTerms = listOf(
    "捣蛋鬼", "验证码", "人工验证", "人机验证", "安全验证", "安全检测", "风险验证", "异常访问",
)
private val riskTerms = listOf(
    "充值", "购买", "支付", "交易", "出售", "卖出", "丢弃", "删除", "重置", "兑换", "消耗", "扣除",
)
private val accountTerms = listOf(
    "登录", "登入", "登陆", "注册", "创建角色", "识别码", "进入游戏", "log in", "sign in", "sign up", "register",
)

private fun isVerification(label: String) = verificationTerms.any { it in label }
private fun isRisk(label: String) = riskTerms.any { it in label }
private fun isManualControl(label: String) =
    isRisk(label) || isVerification(label) || accountTerms.any { label.contains(it, ignoreCase = true) }

internal class RuleRunner(
    private val task: RuleTask,
    private val clock: () -> Long,
    private val log: (Level, String) -> Unit,
) {
    var phase = Phase.IDLE
        private set
    var status = "等待启动"
        private set
    private var startedAt = 0L
    private var deadline = 0L
    private var pausedAt = 0L
    private var endedAt = 0L
    private var pausedMillis = 0L
    private var index = 0
    private var retriesUsed = 0
    private var hasSeenGame = false
    private var tapSent = false
    private var gameReturnDeadline: Long? = null

    val active: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.PAUSED

    val elapsedMillis: Long
        get() {
            val end = when (phase) {
                Phase.IDLE -> return 0
                Phase.RUNNING -> clock()
                Phase.PAUSED -> pausedAt
                else -> endedAt
            }
            return (end - startedAt - pausedMillis).coerceAtLeast(0)
        }

    fun start() {
        check(phase == Phase.IDLE)
        startedAt = clock()
        deadline = startedAt + 10_000
        phase = Phase.RUNNING
        status = "等待游戏显示"
        log(Level.INFO, status)
    }

    fun pause(reason: String = "已手动暂停") {
        if (phase != Phase.RUNNING) return
        pausedAt = clock()
        phase = Phase.PAUSED
        status = reason
        log(Level.WARNING, status)
    }

    fun resume(reopeningGame: Boolean = false) {
        if (phase != Phase.PAUSED) return
        val now = clock()
        val interval = now - pausedAt
        pausedMillis += interval
        deadline += interval
        if (reopeningGame) gameReturnDeadline = now + 10_000
        phase = Phase.RUNNING
        status = "已继续，正在复核当前界面"
        log(Level.INFO, status)
    }

    fun stop(reason: String = "已手动停止") {
        if (active) finish(Phase.STOPPED, Level.INFO, reason)
    }

    fun fail(reason: String) {
        if (active) finish(Phase.ERROR, Level.ERROR, reason)
    }

    fun tick(screen: Screen?) {
        if (phase != Phase.RUNNING) return
        val now = clock()
        if (screen == null) {
            val returnBy = gameReturnDeadline
            if (returnBy != null) {
                if (now >= returnBy) fail("游戏未在前台，请回到角色入口后重新启动")
            } else if (hasSeenGame || now >= deadline) {
                fail("游戏未在前台，请回到角色入口后重新启动")
            }
            return
        }
        val step = task.steps[index]
        gameReturnDeadline?.let {
            deadline += (now - (it - 10_000)).coerceAtLeast(0)
            gameReturnDeadline = null
        }
        if (!hasSeenGame) {
            hasSeenGame = true
            beginStep(now)
        }
        if (screen.controls.any { isVerification(it.text) || isVerification(it.description) }) {
            pause("检测到人工验证，请在游戏中处理后点继续")
            return
        }
        if (screen.controls.any { isRisk(it.text) || isRisk(it.description) }) {
            pause("检测到风险操作界面，请回到任务页面后点继续")
            return
        }
        if (now >= deadline) {
            if (retriesUsed < step.retries) {
                retriesUsed++
                deadline = now + step.timeoutMillis
                log(Level.WARNING, "等待「${step.text}」超时，重新观察（${retriesUsed}/${step.retries}）")
            } else {
                val target = if (tapSent) step.expect else step.text
                fail("等待「$target」超时")
            }
            return
        }
        if (step.expect == null || tapSent) {
            val expected = step.expect ?: step.text
            if (screen.shows(expected)) {
                log(Level.INFO, "已看到「$expected」")
                index++
                retriesUsed = 0
                tapSent = false
                if (index == task.steps.size) {
                    finish(Phase.DONE, Level.INFO, "任务完成")
                } else {
                    beginStep(now)
                }
            }
            return
        }
        if (screen.shows(step.expect)) {
            fail("点击前已出现「${step.expect}」，请确认起始页面")
            return
        }
        val matches = screen.controls.filter { it.text == step.text }
        if (matches.isEmpty()) return
        if (matches.size != 1) {
            fail("控件「${step.text}」匹配到 ${matches.size} 项")
            return
        }
        val control = matches.single()
        val click = control.click
        if (click == null || isManualControl(control.description) || !click()) {
            fail("控件「${step.text}」不可点击，或游戏已切换")
            return
        }
        tapSent = true
        deadline = now + step.timeoutMillis
        status = "已点击「${step.text}」，等待「${step.expect}」"
        log(Level.INFO, status)
    }

    private fun beginStep(now: Long) {
        val step = task.steps[index]
        deadline = now + step.timeoutMillis
        status = "步骤 ${index + 1}/${task.steps.size}，等待「${step.text}」"
        log(Level.DEBUG, status)
    }

    private fun finish(result: Phase, level: Level, reason: String) {
        endedAt = if (phase == Phase.PAUSED) pausedAt else clock()
        phase = result
        status = reason
        log(level, reason)
    }
}
