package io.github.mingshenhk.nitanassistant

import android.content.Context
import org.json.JSONObject

internal object RuleAsset {
    const val NAME = "角色入口与客户端设定往返"

    fun load(context: Context): RuleTask {
        val json = context.assets.open("check_settings_navigation.json")
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(json.getString("package") == GAME_PACKAGE)
        val steps = json.getJSONArray("steps")
        return RuleTask(json.getString("name"), List(steps.length()) { index ->
            val step = steps.getJSONObject(index)
            val expected = when (step.getString("action")) {
                "wait_text" -> null
                "tap_text" -> step.getString("expect_text")
                else -> error("不支持的规则动作")
            }
            Step(
                text = step.getString("text"),
                expect = expected,
                timeoutMillis = step.optInt("timeout_seconds", 15) * 1_000L,
                retries = step.optInt("retries", 0),
            )
        })
    }
}
