package com.xerxes.videodroid

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Local HTTP stub (plain sockets — the Android unit-test classpath has no
 * com.sun.net.httpserver) that behaves like a Range-capable CDN.
 */
class UpdateDownloaderTest {

    private lateinit var server: ServerSocket
    private val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
    private val etag = "\"v1\""
    private val requests = mutableListOf<Pair<String, String?>>()
    private var ignoreRange = false
    private val hits = AtomicInteger(0)
    @Volatile private var running = true

    @Before fun setUp() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (running) {
                val s = try { server.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            var range: String? = null
            var ifRange: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val k = line.substringBefore(":").trim()
                val v = line.substringAfter(":", "").trim()
                if (k.equals("Range", true)) range = v
                if (k.equals("If-Range", true)) ifRange = v
            }
            requests += path to range

            if (path == "/expired") {
                hits.incrementAndGet()
                write(s, 404, emptyMap(), ByteArray(0))
                return
            }
            val start = if (!ignoreRange && range != null && ifRange == etag) {
                range.removePrefix("bytes=").removeSuffix("-").toLong().toInt()
            } else 0
            val body = payload.copyOfRange(start, payload.size)
            val headers = mutableMapOf("ETag" to etag)
            if (start > 0) {
                headers["Content-Range"] = "bytes $start-${payload.size - 1}/${payload.size}"
            }
            write(s, if (start > 0) 206 else 200, headers, body)
        }
    }

    private fun write(s: Socket, code: Int, headers: Map<String, String>, body: ByteArray) {
        val out = s.getOutputStream()
        val sb = StringBuilder("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    @After fun tearDown() {
        running = false
        try { server.close() } catch (_: Throwable) {}
    }

    private fun base() = "http://127.0.0.1:${server.localPort}"
    private fun tmpDest(): File = File.createTempFile("vd-test", ".apk").also { it.delete() }

    @Test fun downloadsFromScratch() {
        val dest = tmpDest()
        val r = UpdateDownloader().download("${base()}/apk", dest)
        assertNull(r.error)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(!File(dest.absolutePath + ".part").exists())
    }

    @Test fun resumesWithRangeAndIfRange() {
        val dest = tmpDest()
        val half = payload.size / 2
        File(dest.absolutePath + ".part").writeBytes(payload.copyOfRange(0, half))
        File(dest.absolutePath + ".etag").writeText(etag)

        val r = UpdateDownloader().download("${base()}/apk", dest, etag = etag)

        assertNull(r.error)
        assertTrue(r.resumed)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(payload.contentEquals(dest.readBytes()))
        assertEquals("bytes=$half-", requests.last().second)
    }

    @Test fun restartsWhenServerIgnoresRange() {
        val dest = tmpDest()
        File(dest.absolutePath + ".part").writeBytes(payload.copyOfRange(0, 1000))
        File(dest.absolutePath + ".etag").writeText(etag)
        ignoreRange = true

        val r = UpdateDownloader().download("${base()}/apk", dest, etag = etag)

        assertNull(r.error)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(payload.contentEquals(dest.readBytes()))
    }

    @Test fun reresolvesExpiredAssetUrlAndKeepsOffset() {
        val dest = tmpDest()
        val half = payload.size / 2
        File(dest.absolutePath + ".part").writeBytes(payload.copyOfRange(0, half))
        File(dest.absolutePath + ".etag").writeText(etag)

        val d = UpdateDownloader(resolver = { "${base()}/apk" })
        val r = d.download("${base()}/expired", dest, etag = etag)

        assertNull(r.error)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(payload.contentEquals(dest.readBytes()))
        assertTrue("expired endpoint must be hit", hits.get() >= 1)
    }

    @Test fun parsesContentRangeTotal() {
        assertEquals(1000L, UpdateDownloader.parseContentRangeTotal("bytes 100-999/1000"))
        assertEquals(4242L, UpdateDownloader.parseContentRangeTotal("bytes 0-10/4242"))
        assertNull(UpdateDownloader.parseContentRangeTotal(null))
        assertNull(UpdateDownloader.parseContentRangeTotal("bytes 0-10/*"))
    }
}
