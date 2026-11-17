package io.github.mingshenhk.nitanassistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.Executors

internal class LocalAiModel(private val file: File, private val cache: File) : AiDecisionModel {
    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    override fun decide(prompt: String, reply: (Result<String>) -> Unit) {
        worker.execute {
            if (closed) return@execute
            val result = try {
                // LiteRT-LM 0.18 can retain state across conversations on the same engine.
                // Reopen the engine for each fresh screen and reuse the compiled weights on disk.
                val response = Engine(EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = 2_048,
                    cacheDir = cache.absolutePath,
                )).use { runtime ->
                    val loading = SystemClock.elapsedRealtime()
                    runtime.initialize()
                    Log.i("NitanLocalModel", "Engine initialized in ${SystemClock.elapsedRealtime() - loading} ms")
                    val started = SystemClock.elapsedRealtime()
                    runtime.createConversation(ConversationConfig(
                        systemInstruction = Contents.of(AI_INSTRUCTIONS.trimIndent()),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                        automaticToolCalling = false,
                        maxOutputToken = 12,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                    )).use { conversation ->
                        conversation.sendMessage(prompt).toString()
                    }.also {
                        Log.i("NitanLocalModel", "Decision generated in ${SystemClock.elapsedRealtime() - started} ms")
                    }
                }
                Result.success(response)
            } catch (error: LinkageError) {
                modelFailure(error, "设备无法加载端侧推理组件")
            } catch (error: Exception) {
                modelFailure(error, "本地模型推理失败，详细原因见设备日志")
            }
            main.post { if (!closed) reply(result) }
        }
    }

    fun close() {
        closed = true
    }

    private fun modelFailure(error: Throwable, message: String): Result<String> {
        Log.w("NitanLocalModel", "LiteRT-LM error (${error.javaClass.simpleName}): ${error.message}")
        return Result.failure(IllegalStateException(message))
    }
}
