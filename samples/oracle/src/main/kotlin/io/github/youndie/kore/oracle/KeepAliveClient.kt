package io.github.youndie.kore.oracle

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** What happened to one request, from the client's side and nowhere else. */
data class Exchange(
    val sentAtNanos: Long,
    val finishedAtNanos: Long,
    /** null when nothing came back — a reset, a truncated response, a read timeout. */
    val status: Int?,
    val headers: Map<String, String>,
    val bodyComplete: Boolean,
    /** The socket this went out on had already carried an earlier request. */
    val connectionReused: Boolean,
    val failure: String?,
) {
    val connectionClose: Boolean get() = headers["connection"]?.lowercase() == "close"
}

/**
 * An HTTP/1.1 client written by hand, on a raw socket.
 *
 * A library client would be easier and would hide the three things every assertion here turns on:
 * whether the connection was **reused**, whether the body **completed** or was cut off, and what the
 * server said about `Connection` on the way out. Those are the observations; the rest of an HTTP
 * client is not needed.
 *
 * One instance is one connection and is used by one thread.
 */
class KeepAliveClient(private val host: String, private val port: Int, private val readTimeoutMillis: Int) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var served = 0

    fun get(path: String): Exchange {
        val reused = socket?.isClosed == false && served > 0
        val sentAt = System.nanoTime()
        try {
            val s = connectIfNeeded()
            val out = s.getOutputStream()
            out.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: keep-alive\r\n\r\n".toByteArray())
            out.flush()
            val response = readResponse(input!!)
            served++
            if (response.headers["connection"]?.lowercase() == "close") close()
            return Exchange(sentAt, System.nanoTime(), response.status, response.headers, response.bodyComplete, reused, null)
        } catch (io: IOException) {
            close()
            return Exchange(sentAt, System.nanoTime(), null, emptyMap(), false, reused, io.toString())
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
    }

    private fun connectIfNeeded(): Socket {
        socket?.takeIf { !it.isClosed }?.let { return it }
        val s = Socket()
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMillis
        s.connect(InetSocketAddress(host, port), readTimeoutMillis)
        socket = s
        input = BufferedInputStream(s.getInputStream())
        served = 0
        return s
    }

    private class Response(val status: Int, val headers: Map<String, String>, val bodyComplete: Boolean)

    private fun readResponse(stream: BufferedInputStream): Response {
        val statusLine = readLine(stream) ?: throw IOException("the connection closed before a status line")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("unparsable status line: $statusLine")

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(stream) ?: throw IOException("the connection closed inside the headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.take(colon).trim().lowercase()] = line.drop(colon + 1).trim()
        }

        // `bodyComplete` is the assertion A1 turns on: a response whose body was cut off is not a
        // response, however well-formed its status line was.
        val complete =
            when {
                headers["transfer-encoding"]?.lowercase() == "chunked" -> readChunked(stream)
                else -> readFixed(stream, headers["content-length"]?.toIntOrNull() ?: 0)
            }
        return Response(status, headers, complete)
    }

    private fun readFixed(stream: BufferedInputStream, length: Int): Boolean {
        var left = length
        val buffer = ByteArray(8 * 1024)
        while (left > 0) {
            val read = stream.read(buffer, 0, minOf(left, buffer.size))
            if (read < 0) return false
            left -= read
        }
        return true
    }

    private fun readChunked(stream: BufferedInputStream): Boolean {
        while (true) {
            val sizeLine = readLine(stream) ?: return false
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return false
            if (size == 0) {
                readLine(stream)
                return true
            }
            if (!readFixed(stream, size)) return false
            readLine(stream)
        }
    }

    private fun readLine(stream: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = stream.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.removeSuffix("\r").toString()
            builder.append(byte.toChar())
        }
    }
}

private fun StringBuilder.removeSuffix(suffix: String): StringBuilder {
    if (length >= suffix.length && substring(length - suffix.length) == suffix) setLength(length - suffix.length)
    return this
}
