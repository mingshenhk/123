package io.github.mingshenhk.nitanassistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

internal class LocalAiModel(private val file: File) : AiDecisionModel {
    companion object {
        private const val TAG = "NitanLocalModel"
        private val worker = Executors.newSingleThreadExecutor()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var closed = false
    private var handle = 0L
    private var loaded = false

    override fun decide(prompt: String, reply: (Result<String>) -> Unit) {
        worker.execute {
            val result = try {
                val session = synchronized(lock) {
                    if (closed) return@execute
                    if (handle == 0L) {
                        System.loadLibrary("nitan_gguf")
                        handle = nativeCreate()
                    }
                    handle
                }
                if (!loaded) {
                    val started = SystemClock.elapsedRealtime()
                    nativeLoad(session, file.absolutePath.toByteArray(), Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    loaded = true
                    Log.i(TAG, "GGUF initialized in ${SystemClock.elapsedRealtime() - started} ms")
                }
                val started = SystemClock.elapsedRealtime()
                val answer = nativeGenerate(session, chatPrompt(prompt).toByteArray())
                Log.i(TAG, "GGUF decision generated in ${SystemClock.elapsedRealtime() - started} ms")
                Result.success(answer)
            } catch (error: LinkageError) {
                modelFailure(error, "设备无法加载端侧推理组件")
            } catch (error: Exception) {
                modelFailure(error, "本地模型推理失败，详细原因见设备日志")
            }
            main.post { if (!closed) reply(result) }
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (handle == 0L) return
            nativeCancel(handle)
        }
        worker.execute {
            synchronized(lock) {
                nativeDestroy(handle)
                handle = 0L
            }
        }
    }

    private fun chatPrompt(prompt: String) = buildString {
        append("<|startoftext|><|im_start|>system\n")
        append(AI_INSTRUCTIONS.trimIndent())
        append("<|im_end|>\n<|im_start|>user\n")
        // The goal and screen text may contain strings that resemble chat control tokens.
        append(prompt.replace('<', '＜'))
        append("<|im_end|>\n<|im_start|>assistant\n<think></think>")
    }

    private fun modelFailure(error: Throwable, message: String): Result<String> {
        if (!closed) Log.w(TAG, "GGUF error (${error.javaClass.simpleName}): ${error.message}")
        return Result.failure(IllegalStateException(message))
    }

    private external fun nativeCreate(): Long
    private external fun nativeLoad(handle: Long, path: ByteArray, threads: Int)
    private external fun nativeGenerate(handle: Long, prompt: ByteArray): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
