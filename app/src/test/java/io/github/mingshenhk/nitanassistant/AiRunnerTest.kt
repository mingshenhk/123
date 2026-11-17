package io.github.mingshenhk.nitanassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRunnerTest {
    private val requests = mutableListOf<Pair<String, (Result<String>) -> Unit>>()
    private var now = 0L
    private var clicks = 0
    private var closed = 0

    private fun runner(goal: String = "打开客户端设定") = AiRunner(
        goal,
        AiDecisionModel { prompt, reply -> requests += prompt to reply },
        { now },
        { _, _ -> },
        { closed++ },
    ).apply { start() }

    private fun button(label: String, location: String = "50%横 30%纵") =
        Control(label, "", location) { clicks++; true }

    private fun reply(value: String, index: Int = requests.lastIndex) =
        requests[index].second(Result.success(value))

    @Test fun tapWaitsForChangedPageAndThenChecksVisibleEvidence() {
        val runner = runner()
        val entry = Screen(listOf(button("游戏设定"), button("进入游戏")))
        val settings = Screen(listOf(Control("客户端设定", "", click = null)))
        runner.tick(entry)
        assertTrue(requests.single().first.contains("1 可点 游戏设定"))
        assertTrue(requests.single().first.contains("2 文字 进入游戏"))

        reply("TAP 1")
        runner.tick(entry)
        runner.tick(entry)
        runner.tick(settings)
        assertEquals(1, requests.size)
        runner.tick(settings)
        reply("DONE 1")
        runner.tick(settings)
        assertEquals(1, clicks)
        assertEquals(Phase.DONE, runner.phase)
        assertEquals(1, closed)
    }

    @Test fun waitsForTextWhenOpeningGame() {
        val runner = runner()
        runner.tick(Screen(emptyList()))
        assertEquals(Phase.RUNNING, runner.phase)
        assertEquals(0, requests.size)
        now = 1_000
        runner.tick(Screen(listOf(button("游戏设定"))))
        assertEquals(1, requests.size)
    }

    @Test fun verifiesModelNavigationFromNewPageTitleWithoutAnotherDecision() {
        val runner = runner()
        val entry = Screen(listOf(button("游戏设定")))
        val settings = Screen(listOf(
            button("返回", "50%横 96%纵"),
            Control("客户端设定", "", "15%横 8%纵", click = null),
        ))
        runner.tick(entry)
        reply("TAP 1")
        runner.tick(entry)
        runner.tick(settings)
        runner.tick(settings)
        assertEquals(1, requests.size)
        assertEquals(Phase.DONE, runner.phase)
        assertEquals("已打开「客户端设定」", runner.status)
    }

    @Test fun resolvesAnExactButtonLabelWithoutAskingTheModel() {
        val runner = runner("打开游戏设定")
        val entry = Screen(listOf(button("更换服务器"), button("游戏设定")))
        val settings = Screen(listOf(
            button("返回", "50%横 96%纵"),
            Control("客户端设定", "", "15%横 8%纵", click = null),
        ))
        runner.tick(entry)
        runner.tick(settings)
        runner.tick(settings)
        assertEquals(0, requests.size)
        assertEquals(1, clicks)
        assertEquals(Phase.DONE, runner.phase)
        assertEquals("已打开「客户端设定」", runner.status)
    }

    @Test fun rejectsPlanAfterButtonsOrWindowChange() {
        val runner = runner()
        runner.tick(Screen(listOf(button("游戏设定")), windowId = 1))
        reply("TAP 1")
        val differentWindow = Screen(listOf(button("游戏设定")), windowId = 2)
        runner.tick(differentWindow)
        runner.tick(differentWindow)
        reply("TAP 1")
        runner.tick(Screen(listOf(button("游戏设定", "50%横 60%纵")), windowId = 2))
        assertEquals(0, clicks)
        assertEquals(2, requests.size)
    }

    @Test fun pauseDiscardsOutstandingActionAndVerificationPreventsNewRequest() {
        val runner = runner()
        val screen = Screen(listOf(button("游戏设定")))
        runner.tick(screen)
        runner.tick(Screen(listOf(button("游戏设定"), button("人机验证"))))
        assertEquals(Phase.PAUSED, runner.phase)
        runner.resume()
        runner.tick(screen)
        reply("TAP 1", index = 0)
        runner.tick(screen)
        assertEquals(0, clicks)
        assertEquals(2, requests.size)
        runner.stop()
        reply("TAP 1")
        runner.tick(screen)
        assertEquals(0, clicks)
    }

    @Test fun sameLabelAtDifferentLocationsBindsToSelectedControl() {
        val runner = runner()
        var selected = -1
        val screen = Screen(listOf(
            Control("炼妖炉", "", "10%横 30%纵") { selected = 1; true },
            Control("炼妖炉", "", "10%横 40%纵") { selected = 2; true },
        ))
        runner.tick(screen)
        assertTrue(requests.single().first.contains("2 可点 炼妖炉 @10%横 40%纵"))
        reply("TAP 2")
        runner.tick(screen)
        assertEquals(2, selected)
    }

    @Test fun exposesOneLabelPerAliasAndAcceptsTheAnswerAfterAThinkingTag() {
        val runner = runner()
        val entry = Screen(listOf(
            Control("", "游戏设定", "40%横 60%纵", clickGroup = 1) { clicks++; true },
            Control("游戏设定", "", "41%横 61%纵", clickGroup = 1) { clicks++; true },
        ))
        runner.tick(entry)
        val prompt = requests.single().first
        assertEquals(listOf("1 可点 游戏设定"), prompt.lines().filter { it.contains("可点") })
        reply("TAP 1</think>TAP 1\n\n用户目标")
        runner.tick(entry)
        assertEquals(1, clicks)
    }

    @Test fun riskConfirmationPausesBeforeAskingModel() {
        val runner = runner()
        runner.tick(Screen(listOf(Control("是否丢弃物品？", "", click = null), button("确定"))))
        assertEquals(Phase.PAUSED, runner.phase)
        assertEquals(0, requests.size)
        assertEquals(0, clicks)
    }

    @Test fun acceptsTheModelClickCallAsOneValidatedAction() {
        val runner = runner()
        val screen = Screen(listOf(button("游戏设定")))
        runner.tick(screen)
        reply("<tool_call>\n<function=click button 1>\n</function>\n</tool_call>")
        runner.tick(screen)
        assertEquals(1, clicks)
    }

    @Test fun rejectsReadOnlyTargetAndMultipleActions() {
        for (answer in listOf("TAP 2", "TAP 1\nTAP 1", "TAP 8", "TAP 1\n<tool_call>")) {
            val runner = runner()
            val screen = Screen(listOf(button("打开"), Control("提示", "", click = null)))
            runner.tick(screen)
            reply(answer)
            runner.tick(screen)
            assertEquals(Phase.ERROR, runner.phase)
            assertEquals(0, clicks)
        }
    }

    @Test fun stopsWhenTapDoesNotChangeThePage() {
        val runner = runner()
        val screen = Screen(listOf(button("游戏设定")))
        runner.tick(screen)
        reply("TAP 1")
        runner.tick(screen)
        now = 8_001
        runner.tick(screen)
        assertEquals(1, clicks)
        assertEquals(1, requests.size)
        assertEquals(Phase.ERROR, runner.phase)
    }
}
