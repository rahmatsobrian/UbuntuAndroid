package dev.ubuntu4a.core.rootfs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

sealed interface DownloadState {
    data class Resuming(val from: Long) : DownloadState
    data class Progress(val transferred: Long, val total: Long, val bytesPerSec: Long) : DownloadState
    data class Checksum(val expected: String?, val actual: String, val verified: Boolean) : DownloadState
    data class Done(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class RootfsDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun download(url: String, dest: File, expectedSha256: String? = null): Flow<DownloadState> = flow {
        val part = File(dest.parentFile, dest.name + ".part")
        var start = if (part.exists()) part.length() else 0L
        try {
            val request = Request.Builder()
                .url(url)
                .apply { if (start > 0) header("Range", "bytes=$start-") }
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(DownloadState.Failed("HTTP ${resp.code} from ${resp.request.url}"))
                    return@use
                }
                val body = resp.body
                if (body == null) {
                    emit(DownloadState.Failed("Empty response body"))
                    return@use
                }
                if (start > 0 && resp.code != 206) start = 0L
                if (start > 0) emit(DownloadState.Resuming(start))
                val declared = body.contentLength()
                val total = if (declared > 0) declared + start else -1L
                var transferred = start
                FileOutputStream(part, start > 0).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastTick = System.currentTimeMillis()
                        var lastBytes = transferred
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            transferred += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 200) {
                                val speed = ((transferred - lastBytes) * 1000L / max(1, now - lastTick))
                                lastTick = now; lastBytes = transferred
                                emit(DownloadState.Progress(transferred, total, speed))
                            }
                        }
                    }
                }
                val expected = expectedSha256 ?: tryFetchChecksum(url, part.name)
                val actual = Checksums.sha256(part)
                val verified = expected == null || expected.equals(actual, true)
                emit(DownloadState.Checksum(expected, actual, verified))
                if (!verified) {
                    part.delete()
                    emit(DownloadState.Failed("SHA-256 mismatch. Expected $expected, got $actual"))
                    return@flow
                }
                if (dest.exists()) dest.delete()
                part.renameTo(dest)
                emit(DownloadState.Done(dest))
            }
        } catch (t: Throwable) {
            emit(DownloadState.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private fun tryFetchChecksum(url: String, fileName: String): String? = try {
        val dir = url.substringBeforeLast('/')
        val req = Request.Builder().url("$dir/SHA256SUMS").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val text = r.body?.string() ?: return@use null
            Regex("([0-9a-fA-F]{64})\\s+\\*?/?$fileName")
                .find(text)?.groupValues?.get(1)
        }
    } catch (_: Throwable) {
        null
    }
}
