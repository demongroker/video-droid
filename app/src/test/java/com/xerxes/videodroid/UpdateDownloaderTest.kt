package com.xerxes.videodroid

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class UpdateDownloaderTest {

    private lateinit var server: HttpServer
    private val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
    private var etag = "\"v1\""
    private val requests = mutableListOf<Pair<String, String?>>()  // path to Range header
    private var ignoreRange = false
    private var expireFirstUrl = false
    private val hits = AtomicInteger(0)

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            requests += ex.requestURI.path to range
            if (expireFirstUrl && ex.requestURI.path == "/apk-expired") {
                ex.sendResponseHeaders(404, -1); ex.close(); return@createContext
            }
            val start = if (!ignoreRange && range != null && ifRange == etag) {
                range.removePrefix("bytes=").removeSuffix("-").toLong().toInt()
            } else 0
            val code = if (start > 0) 206 else 200
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            }
            ex.responseHeaders.add("ETag", etag)
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/expired") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(404, -1); ex.close()
        }
        server.start()
    }

    @After fun tearDown() { server.stop(0) }

    private fun base() = "http://127.0.0.1:${server.address.port}"
    private fun tmpDest(): File = File.createTempFile("vd-test", ".apk").also { it.delete() }

    @Test fun downloadsFromScratch() {
        val dest = tmpDest()
        val r = UpdateDownloader().download("${base()}/apk", dest)
        assertNotNull(r.error, r.file)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(File(dest.absolutePath + ".part").exists().not())
    }

    @Test fun resumesWithRangeAndIfRange() {
        val dest = tmpDest()
        val part = File(dest.absolutePath + ".part")
        val half = payload.size / 2
        part.writeBytes(payload.copyOfRange(0, half))
        File(dest.absolutePath + ".etag").writeText(etag)

        val r = UpdateDownloader().download("${base()}/apk", dest, etag = etag)

        assertNull(r.error)
        assertTrue(r.resumed)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(payload.contentEquals(dest.readBytes()))
        val last = requests.last()
        assertEquals("bytes=$half-", last.second)
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
        assertTrue("expired endpoint must be hit once", hits.get() >= 1)
    }

    @Test fun parsesContentRangeTotal() {
        assertEquals(1000L, UpdateDownloader.parseContentRangeTotal("bytes 100-999/1000"))
        assertEquals(4242L, UpdateDownloader.parseContentRangeTotal("bytes 0-10/4242"))
        assertNull(UpdateDownloader.parseContentRangeTotal(null))
        assertNull(UpdateDownloader.parseContentRangeTotal("bytes 0-10/*"))
    }
}
