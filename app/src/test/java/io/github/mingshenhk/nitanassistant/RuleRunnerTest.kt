package io.github.mingshenhk.nitanassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRunnerTest {
    private var now = 0L
    private var clicks = 0
    private val messages = mutableListOf<Pair<Level, String>>()

    private fun start(vararg steps: Step): RuleRunner =
        RuleRunner(RuleTask("测试", steps.toList()), { now }) { level, text ->
            messages += level to text
        }.apply { start() }

    private fun screen(vararg labels: String) = Screen(labels.map { label ->
        Control(label, "") { clicks++; true }
    })

    @Test fun navigationConfirmsEachNewPage() {
        val runner = start(Step("进入游戏"), Step("游戏设定", "客户端设定"), Step("返回", "进入游戏"))
        runner.tick(screen("进入游戏", "游戏设定"))
        runner.tick(screen("进入游戏", "游戏设定"))
        runner.tick(screen("客户端设定", "返回"))
        runner.tick(screen("客户端设定", "返回"))
        runner.tick(screen("进入游戏"))
        assertEquals(2, clicks)
        assertEquals(Phase.DONE, runner.phase)
        assertTrue(messages.any { it.first == Level.DEBUG })
        assertEquals(Level.INFO to "任务完成", messages.last())
    }

    @Test fun verificationPausesAndResumeChecksAgain() {
        val runner = start(Step("游戏设定", "客户端设定"))
        runner.tick(screen("游戏设定", "验证码"))
        assertEquals(Phase.PAUSED, runner.phase)
        now = 20_000
        runner.resume()
        runner.tick(screen("捣蛋鬼", "游戏设定"))
        assertEquals(Phase.PAUSED, runner.phase)
        assertEquals(0, clicks)
        runner.resume()
        runner.tick(screen("游戏设定"))
        runner.tick(screen("客户端设定"))
        assertEquals(Phase.DONE, runner.phase)
    }

    @Test fun pauseAfterClickKeepsAwaitingTheResult() {
        val runner = start(Step("游戏设定", "客户端设定", 1_000))
        runner.tick(screen("游戏设定"))
        now = 300
        runner.pause()
        now = 50_000
        runner.tick(screen("客户端设定"))
        assertEquals(300L, runner.elapsedMillis)
        runner.resume()
        runner.tick(screen("游戏设定"))
        runner.tick(screen("客户端设定"))
        assertEquals(1, clicks)
        assertEquals(Phase.DONE, runner.phase)
    }

    @Test fun stoppedTaskCannotClickAgain() {
        val runner = start(Step("游戏设定", "客户端设定"))
        runner.pause()
        runner.stop()
        runner.resume()
        runner.tick(screen("游戏设定"))
        assertFalse(runner.active)
        assertEquals(Phase.STOPPED, runner.phase)
        assertEquals(0, clicks)
    }

    @Test fun unavailableGameTimesOutAndSwitchingAwayStopsAnActiveTask() {
        val runner = start(Step("游戏设定", "客户端设定"))
        runner.tick(null)
        assertEquals(Phase.RUNNING, runner.phase)
        now = 10_000
        runner.tick(null)
        assertEquals(Phase.ERROR, runner.phase)

        val active = start(Step("进入游戏"), Step("游戏设定", "客户端设定"))
        active.tick(screen("进入游戏"))
        active.tick(null)
        active.tick(screen("游戏设定"))
        assertEquals(Phase.ERROR, active.phase)
        assertEquals(0, clicks)
    }

    @Test fun explicitResumeWaitsForGameToReturn() {
        val runner = start(Step("游戏设定", "客户端设定", 1_000))
        runner.tick(screen("入口"))
        runner.pause()
        runner.resume(reopeningGame = true)
        now = 1_100
        runner.tick(null)
        assertTrue(runner.active)
        runner.tick(screen("游戏设定"))
        runner.tick(screen("客户端设定"))
        assertEquals(Phase.DONE, runner.phase)
    }

    @Test fun missingControlStopsAfterItsTimeout() {
        val runner = start(Step("游戏设定", "客户端设定", 1_000))
        runner.tick(screen("加载中"))
        now = 1_000
        runner.tick(screen("加载中"))
        assertEquals(0, clicks)
        assertEquals(Phase.ERROR, runner.phase)
        assertEquals(Level.ERROR, messages.last().first)
    }

    @Test fun timesOutAfterOneClickWhenExpectedPageNeverAppears() {
        val runner = start(Step("游戏设定", "客户端设定", 1_000))
        runner.tick(screen("游戏设定"))
        now = 950
        runner.tick(screen("游戏设定"))
        now = 1_000
        runner.tick(screen("游戏设定"))
        assertEquals(1, clicks)
        assertEquals(Phase.ERROR, runner.phase)
    }

    @Test fun observeRetriesAreBounded() {
        val runner = start(Step("入口", timeoutMillis = 1_000, retries = 2))
        runner.tick(screen("加载中"))
        repeat(3) {
            now += 1_000
            runner.tick(screen("加载中"))
        }
        assertEquals(2, messages.count { it.second.contains("重新观察") })
        assertEquals(Phase.ERROR, runner.phase)
    }

    @Test fun checksPreexistingResultAndAmbiguousButtons() {
        val resultVisible = start(Step("游戏设定", "客户端设定"))
        resultVisible.tick(screen("游戏设定", "客户端设定"))
        assertEquals(Phase.ERROR, resultVisible.phase)
        val multiple = start(Step("游戏设定", "客户端设定"))
        multiple.tick(screen("游戏设定", "游戏设定"))
        assertEquals(Phase.ERROR, multiple.phase)
        assertEquals(0, clicks)
    }

    @Test fun tapFailureAndPermissionLossStop() {
        val targetGone = start(Step("游戏设定", "客户端设定"))
        targetGone.tick(Screen(listOf(Control("游戏设定", "") { false })))
        assertEquals(Phase.ERROR, targetGone.phase)
        val revoked = start(Step("游戏设定", "客户端设定"))
        revoked.fail("无障碍服务已关闭")
        revoked.tick(screen("游戏设定"))
        assertEquals(Phase.ERROR, revoked.phase)
        assertEquals(0, clicks)
    }
}
