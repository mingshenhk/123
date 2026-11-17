package io.github.mingshenhk.nitanassistant

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

internal enum class AiModelSpec(
    val title: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val size: String,
    val page: String,
) {
    LFM_350M(
        "LFM2.5-350M",
        "LFM2.5-350M_int4.litertlm",
        247_808_000L,
        "c29a41cdf4148ec9f4b6e57f8e598728a05a189fdc7ca0764b013b78f9dfd52f",
        "248 MB",
        "https://huggingface.co/litert-community/LFM2.5-350M/blob/6aaa502a5576e260ca86d75e101641d6f9ffab41/LFM2.5-350M_int4.litertlm",
    ),
    LFM_17B(
        "LFM3-1.7B",
        "LFM3-1.7B_mixed_int4_ekv2048.litertlm",
        1_441_620_928L,
        "0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196",
        "1.44 GB",
        "https://huggingface.co/litert-community/LFM3-1.7B-LiteRT/blob/main/LFM3-1.7B_mixed_int4_ekv2048.litertlm",
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id }
    }
}

internal object ModelStore {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var importing = false
        private set
    @Volatile private var lastError: Pair<AiModelSpec, String>? = null

    val supported get() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun ready(context: Context, model: AiModelSpec) =
        file(context, model).takeIf { it.isFile && it.length() == model.bytes }

    fun error(model: AiModelSpec) = lastError?.takeIf { it.first == model }?.second

    // Called from the activity thread. Copy and verify before publishing the file.
    fun import(context: Context, uri: Uri, model: AiModelSpec) {
        if (importing || ready(context, model) != null) return
        val app = context.applicationContext
        val dest = file(app, model)
        importing = true
        lastError = null
        worker.execute {
            val temp = File(dest.parentFile, "${model.fileName}.partial")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val directory = checkNotNull(dest.parentFile)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                if (directory.usableSpace < model.bytes) {
                    throw IOException("助手的存储空间不足，需要约 ${model.size}")
                }
                app.contentResolver.openInputStream(uri)?.use { source ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val length = source.read(buffer)
                            if (length == -1) break
                            total += length
                            if (total > model.bytes) throw IOException("文件大小不匹配，请下载指定版本")
                            digest.update(buffer, 0, length)
                            output.write(buffer, 0, length)
                        }
                    }
                } ?: throw IOException()
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (temp.length() != model.bytes || hash != model.sha256) {
                    throw IOException("模型校验失败，请重新下载指定版本")
                }
                if (!temp.renameTo(dest)) throw IOException()
            } catch (exception: IOException) {
                val message = exception.message?.takeIf { it.startsWith("助手") || it.startsWith("模型") ||
                    it.startsWith("文件大小") } ?: "无法读取或保存模型，请检查文件和可用空间"
                lastError = model to message
            } catch (_: SecurityException) {
                lastError = model to "无法读取所选文件，请重新选择模型"
            } finally {
                temp.delete()
                importing = false
            }
        }
    }

    private fun file(context: Context, model: AiModelSpec) = File(context.filesDir, "models/${model.fileName}")
}
