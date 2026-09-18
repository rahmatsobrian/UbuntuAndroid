package dev.ubuntu4a.core.vnc

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.experimental.xor

/**
 * Minimal Websockify: accepts one WebSocket client (the bundled noVNC
 * viewer) and relays bytes to a TCP socket (VNC server in the container).
 * Handles only what RFB over noVNC needs: binary frames, fragmentation and
 * ping/pong; no TLS (loopback only).
 */
class WebsockifyBridge(
    private val listenPort: Int,
    private val targetHost: String = "127.0.0.1",
    private val targetPort: Int = 5900,
) {
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "websockify").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", listenPort))
        }
        running = true
        pool.execute {
            while (running) {
                val client = runCatching { server?.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.tcpNoKeepAlive()
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val request = readHttpHead(input)
            val key = request.headers["sec-websocket-key"] ?: throw IOException("missing ws key")
            val accept = WebSocketCodec.acceptKey(key)
            val subprotocol = request.headers["sec-websocket-protocol"]
                ?.split(',')?.map { it.trim() }
                ?.firstOrNull { it.equals("binary", true) || it.equals("rfb", true) }
            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $accept\r\n")
                if (subprotocol != null) append("Sec-WebSocket-Protocol: $subprotocol\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray())
            output.flush()

            val target = Socket().apply {
                connect(InetSocketAddress(targetHost, targetPort), 10_000)
            }
            val fromTarget = target.getInputStream()
            val toTarget = target.getOutputStream()

            val writer = thread(isDaemon = true, name = "ws-write") {
                try {
                    val buf = ByteArray(32 * 1024)
                    while (running) {
                        val n = fromTarget.read(buf)
                        if (n < 0) break
                        output.write(WebSocketCodec.frame(buf, 0, n))
                        output.flush()
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { client.close() }
                    runCatching { target.close() }
                }
            }

            try {
                WebSocketFrameReader(input).useFrames { data ->
                    toTarget.write(data)
                    toTarget.flush()
                }
            } catch (_: IOException) {
            } finally {
                writer.interrupt()
                runCatching { target.close() }
                runCatching { client.close() }
            }
        } catch (_: Throwable) {
            runCatching { client.close() }
        }
    }

    private fun Socket.tcpNoKeepAlive() {
        runCatching { keepAlive = true }
    }

    private fun readHttpHead(input: InputStream): HttpRequest {
        val text = StringBuilder()
        var prev = 0
        var lf = false
        while (true) {
            val b = input.read()
            if (b < 0) break
            text.append(b.toChar())
            if (lf && prev == '\n'.code) break
            lf = b == '\r'.code || b == '\n'.code
            prev = b
        }
        return HttpRequest.parse(text.toString())
    }
}

class HttpRequest(val method: String, val path: String, val headers: Map<String, String>) {
    companion object {
        fun parse(raw: String): HttpRequest {
            val lines = raw.split("\r\n", "\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: listOf("", "")
            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            return HttpRequest(requestLine.getOrElse(0) { "" }, requestLine.getOrElse(1) { "/" }, headers)
        }
    }
}

object WebSocketCodec {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun acceptKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    fun frame(payload: ByteArray, offset: Int, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(length + 10)
        out.write(0x82)
        when {
            length < 126 -> out.write(length)
            length <= 0xFFFF -> {
                out.write(126)
                out.write(length ushr 8); out.write(length)
            }
            else -> {
                out.write(127)
                for (i in 7 downTo 0) out.write(length.toLong().shr(i * 8).toInt())
            }
        }
        out.write(payload, offset, length)
        return out.toByteArray()
    }
}

class WebSocketFrameReader(private val input: InputStream) {
    fun useFrames(handler: (ByteArray) -> Unit) {
        val collected = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val b1 = input.read()
            if (b1 < 0) return
            val masked = b1 and 0x80 != 0
            var len = b1 and 0x7F
            if (len == 126) {
                len = (input.read() shl 8) or input.read()
            } else if (len == 127) {
                var l = 0L
                repeat(8) { l = (l shl 8) or input.read().toLong() }
                len = l.toInt()
            }
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(len)
            readFully(payload)
            if (mask != null) for (i in payload.indices) payload[i] = payload[i] xor mask[i % 4]
            when (opcode) {
                0x0, 0x1, 0x2 -> {
                    collected.write(payload)
                    if (fin) {
                        handler(collected.toByteArray())
                        collected.reset()
                    }
                }
                0x8 -> return
                0x9 -> { /* ping: noVNC never sends; ignored */ }
                0xA -> { /* pong */ }
            }
        }
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }
}
