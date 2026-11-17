package io.github.mingshenhk.nitanassistant

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

internal object ModelStore {
    const val PAGE = "https://huggingface.co/litert-community/LFM3-1.7B-LiteRT"
    const val NAME = "LFM3-1.7B_mixed_int4_ekv2048.litertlm"
    private const val BYTES = 1_441_620_928L
    private const val SHA256 = "0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var importing = false
        private set
    @Volatile var error: String? = null
        private set

    val supported get() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun ready(context: Context) = file(context).takeIf { it.isFile && it.length() == BYTES }

    // Called from the activity thread. Copy and verify before publishing the file.
    fun import(context: Context, uri: Uri) {
        if (importing || ready(context) != null) return
        val app = context.applicationContext
        val dest = file(app)
        importing = true
        error = null
        worker.execute {
            val temp = File(dest.parentFile, "$NAME.partial")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val directory = checkNotNull(dest.parentFile)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                if (directory.usableSpace < BYTES) {
                    throw IOException("助手的存储空间不足，需要约 1.44 GB")
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
                if (temp.length() != BYTES || digest.digest().joinToString("") { "%02x".format(it) } != SHA256) {
                    throw IOException("模型校验失败，请重新下载指定版本")
                }
                if (!temp.renameTo(dest)) throw IOException()
            } catch (exception: IOException) {
                error = exception.message?.takeIf { it.startsWith("助手") || it.startsWith("模型") ||
                    it.startsWith("文件大小") } ?: "无法读取或保存模型，请检查文件和可用空间"
            } catch (_: SecurityException) {
                error = "无法读取所选文件，请重新选择模型"
            } finally {
                temp.delete()
                importing = false
            }
        }
    }

    private fun file(context: Context) = File(context.filesDir, "models/$NAME")
}
