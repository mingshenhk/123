package io.github.mingshenhk.nitanassistant

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object ModelStore {
    const val TITLE = "LFM3-1.7B · Q4_K_M GGUF"
    const val FILE_NAME = "LFM3-1.7B-Q4_K_M.gguf"
    const val SIZE = "1.10 GB"
    const val PAGE = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/blob/b3f2ae5d73cd3c91bf40e8ba752c648aa05dffdf/LFM3-1.7B-Q4_K_M.gguf"
    private const val BYTES = 1_099_627_808L
    private const val SHA256 = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var importing = false
        private set
    @Volatile var lastError: String? = null
        private set

    val supported get() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun ready(context: Context) =
        file(context).takeIf { it.isFile && it.length() == BYTES }

    // Called from the activity thread. Copy and verify before publishing the file.
    fun import(context: Context, uri: Uri) {
        if (importing || ready(context) != null) return
        val app = context.applicationContext
        val dest = file(app)
        importing = true
        lastError = null
        worker.execute {
            val temp = File(dest.parentFile, "$FILE_NAME.partial")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val directory = checkNotNull(dest.parentFile)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                if (directory.usableSpace < BYTES) {
                    throw IOException("助手的存储空间不足，需要约 $SIZE")
                }
                app.contentResolver.openInputStream(uri)?.use { source ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val length = source.read(buffer)
                            if (length == -1) break
                            total += length
                            if (total > BYTES) throw IOException("文件大小不匹配，请下载指定版本")
                            digest.update(buffer, 0, length)
                            output.write(buffer, 0, length)
                        }
                    }
                } ?: throw IOException()
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (temp.length() != BYTES || hash != SHA256) {
                    throw IOException("模型校验失败，请重新下载指定版本")
                }
                if (!temp.renameTo(dest)) throw IOException()
            } catch (exception: IOException) {
                val message = exception.message?.takeIf { it.startsWith("助手") || it.startsWith("模型") ||
                    it.startsWith("文件大小") } ?: "无法读取或保存模型，请检查文件和可用空间"
                lastError = message
            } catch (_: SecurityException) {
                lastError = "无法读取所选文件，请重新选择模型"
            } finally {
                temp.delete()
                importing = false
            }
        }
    }

    private fun file(context: Context) = File(context.filesDir, "models/$FILE_NAME")
}
