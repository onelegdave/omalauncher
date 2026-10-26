package dev.omalauncher.linux

import dev.omalauncher.linux.RawHttpServer.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL
import java.util.concurrent.CancellationException

class RootfsDownloadTest {
    @Test fun explicitCancelWhileWaitingForHeadersDoesNotBecomeANetworkRetry() {
        val requested = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val s = server {
            requested.countDown()
            release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            Response(200, body)
        }
        val d = downloader(DownloadLimits(connectTimeoutMs = 500, readTimeoutMs = 500))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> { runCatching { d.signature("${s.base}/a.sig") }.exceptionOrNull() }
            assertTrue(requested.await(2, java.util.concurrent.TimeUnit.SECONDS))
            d.cancel()
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is CancellationException)
            assertEquals(1, s.requests.size)
            assertFalse(d.archive.exists())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @get:Rule val tmp = TemporaryFolder()

    private val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val other = ByteArray(300_000) { (it * 17 % 241).toByte() }
    private val servers = ArrayList<RawHttpServer>()

    @After fun stop() = servers.forEach { it.close() }

    private fun server(handler: (RawHttpServer.Request) -> Response) = RawHttpServer(handler).also { servers += it }

    private val cache by lazy { tmp.newFolder("cache") }
    private val partial get() = File(cache, "$ROOTFS_TARBALL.part")
    private val partialInfo get() = File(cache, "$ROOTFS_TARBALL.part.json")

    private fun downloader(
        limits: DownloadLimits = DownloadLimits(),
        ensureActive: () -> Unit = {},
    ) = RootfsDownloader(cache, limits, { it.host == "127.0.0.1" }, ensureActive)

    /** Serves [content] like nginx: ETag (unless null), Last-Modified (if set), ranges, If-Range with either. */
    private fun ranged(
        content: () -> ByteArray,
        etag: () -> String? = { "\"v1\"" },
        cutFirstAt: Int? = null,
        modified: String? = null,
    ): (RawHttpServer.Request) -> Response {
        var first = true
        return handler@{ req ->
            val bytes = content()
            val headers = listOfNotNull(etag()?.let { "ETag" to it }, modified?.let { "Last-Modified" to it }, "Accept-Ranges" to "bytes").toMap()
            val range = req.header("Range")
            val ifRange = req.header("If-Range")
            if (range != null && (ifRange == null || ifRange == etag() || ifRange == modified)) {
                val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                if (start >= bytes.size) return@handler Response(416)
                val part = bytes.copyOfRange(start, bytes.size)
                return@handler Response(206, part, headers + ("Content-Range" to "bytes $start-${bytes.size - 1}/${bytes.size}"))
            }
            val cut = if (first) cutFirstAt else null
            first = false
            Response(200, bytes, headers, sendOnly = cut)
        }
    }

    @Test fun downloadsAWholeFile() {
        val s = server(ranged({ body }))
        val file = downloader().download("${s.base}/a.tar.gz")
        assertArrayEquals(body, file.readBytes())
        assertFalse(partial.exists() || partialInfo.exists())
        assertEquals("identity", s.requests.single().header("Accept-Encoding"))
    }

    @Test fun resumesWithAMatchingRangeAndValidator() {
        val s = server(ranged({ body }, cutFirstAt = 100_000))
        val url = "${s.base}/a.tar.gz"
        val e = assertThrows(RootfsException::class.java) { downloader().download(url) }
        // It got somewhere and can be continued: the next try should come back here.
        assertEquals(RootfsException.Kind.Interrupted, e.kind)
        assertTrue(e.kind.retryable)
        assertEquals(100_000L, partial.length())
        assertArrayEquals(body, downloader().download(url).readBytes())
        val resume = s.requests[1]
        assertEquals("bytes=100000-", resume.header("Range"))
        assertEquals("\"v1\"", resume.header("If-Range"))
    }

    @Test fun restartsWhenTheServerIgnoresTheRange() {
        var calls = 0
        val s =
            server { _ ->
                if (calls++ == 0) Response(200, body, mapOf("ETag" to "\"v1\""), sendOnly = 100_000) else Response(200, body, mapOf("ETag" to "\"v1\""))
            }
        val url = "${s.base}/a.tar.gz"
        assertThrows(RootfsException::class.java) { downloader().download(url) }
        // A 200 to a range request is the whole file: written from the start, not appended.
        assertArrayEquals(body, downloader().download(url).readBytes())
    }

    @Test fun aServerThatIgnoresRangesIsntComeBackToWhenItBreaksOff() {
        // It says it takes ranges, then answers every one with the whole file, and breaks off each time.
        val s = server { Response(200, body, mapOf("ETag" to "\"v1\"", "Accept-Ranges" to "bytes"), sendOnly = 100_000) }
        val url = "${s.base}/a.tar.gz"
        // The first cut can be continued, as far as anyone knows.
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { downloader().download(url) }.kind)
        // Asked to continue, it started over: the next cut isn't a reason to come back, so another mirror gets a turn.
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download(url) }.kind)
        assertEquals("bytes=100000-", s.requests[1].header("Range"))
        // What it sent is still kept, for a mirror that can continue it.
        assertEquals(100_000L, partial.length())
        assertEquals(url, downloader().partialUrl)
    }

    @Test fun aServerWhoseRangesDontFitIsntComeBackToEither() {
        // Ranges answered with the wrong part; the whole file, asked for then, breaks off every time.
        val s =
            server { req ->
                val headers = mapOf("ETag" to "\"v1\"", "Accept-Ranges" to "bytes")
                if (req.header("Range") != null) {
                    Response(206, body, headers + ("Content-Range" to "bytes 0-299999/300000"))
                } else {
                    Response(200, body, headers, sendOnly = 100_000)
                }
            }
        val url = "${s.base}/a.tar.gz"
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { downloader().download(url) }.kind)
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download(url) }.kind)
        assertEquals(listOf(null, "bytes=100000-", null), s.requests.map { it.header("Range") })
    }

    @Test fun aCutWithoutAdvertisedRangesIsntComeBackTo() {
        val s = server { Response(200, body, mapOf("ETag" to "\"v1\""), sendOnly = 100_000) }
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a") }.kind)
        assertEquals(100_000L, partial.length())
    }

    @Test fun restartsWhenTheFileChanged() {
        var current = body
        var tag = "\"v1\""
        val s = server(ranged({ current }, { tag }, cutFirstAt = 100_000))
        val url = "${s.base}/a.tar.gz"
        assertThrows(RootfsException::class.java) { downloader().download(url) }
        current = other
        tag = "\"v2\""
        // If-Range no longer matches: the server sends the new file whole, which replaces the old part.
        assertArrayEquals(other, downloader().download(url).readBytes())
    }

    private fun resumeAgainst(bad: Response): Pair<RawHttpServer, ByteArray> {
        var calls = 0
        val s =
            server { req ->
                when {
                    calls++ == 0 -> Response(200, body, mapOf("ETag" to "\"v1\""), sendOnly = 100_000)
                    req.header("Range") != null -> bad
                    else -> Response(200, body, mapOf("ETag" to "\"v1\""))
                }
            }
        val url = "${s.base}/a.tar.gz"
        assertThrows(RootfsException::class.java) { downloader().download(url) }
        return s to downloader().download(url).readBytes()
    }

    @Test fun neverAppendsAPartialReplyThatDoesntContinueTheFile() {
        val tail = body.copyOfRange(100_000, body.size)
        val wrongStart = Response(206, tail, mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 99000-299999/300000"), contentLength = tail.size.toLong())
        val wrongTotal = Response(206, tail, mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 100000-299999/400000"))
        val wrongTag = Response(206, tail, mapOf("ETag" to "\"v2\"", "Content-Range" to "bytes 100000-299999/300000"))
        val noRange = Response(206, tail, mapOf("ETag" to "\"v1\""))
        val notToEnd = Response(206, tail.copyOf(1000), mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 100000-100999/300000"))
        val lengthMismatch = Response(206, tail, mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 100000-299999/300000"), contentLength = 5)
        for (bad in listOf(wrongStart, wrongTotal, wrongTag, noRange, notToEnd)) {
            partial.delete()
            partialInfo.delete()
            File(cache, ROOTFS_TARBALL).delete()
            val (s, result) = resumeAgainst(bad)
            assertArrayEquals(body, result)
            // Restarted from the beginning, without a range.
            assertNull(s.requests.last().header("Range"))
        }
        // A Content-Length that disagrees with Content-Range: dropped, never appended.
        partial.delete()
        partialInfo.delete()
        File(cache, ROOTFS_TARBALL).delete()
        val (_, result) = resumeAgainst(lengthMismatch)
        assertArrayEquals(body, result)
    }

    @Test fun recoversFrom416() {
        val (s, result) = resumeAgainst(Response(416))
        assertArrayEquals(body, result)
        assertNull(s.requests.last().header("Range"))
    }

    @Test fun givesUpOnAServerThatKeepsMisbehaving() {
        var calls = 0
        val s =
            server { _ ->
                if (calls++ == 0) Response(200, body, mapOf("ETag" to "\"v1\""), sendOnly = 100_000) else Response(206, body, mapOf("ETag" to "\"v1\""))
            }
        val url = "${s.base}/a.tar.gz"
        assertThrows(RootfsException::class.java) { downloader().download(url) }
        val e = assertThrows(RootfsException::class.java) { downloader().download(url) }
        assertEquals(RootfsException.Kind.Network, e.kind)
        assertFalse(File(cache, ROOTFS_TARBALL).exists())
    }

    @Test fun acceptsUnknownLengths() {
        val s = server { Response(200, body, contentLength = null) }
        assertArrayEquals(body, downloader().download("${s.base}/a.tar.gz").readBytes())
    }

    @Test fun aShortReadKeepsOnlyResumableState() {
        val s = server { Response(200, body, mapOf("ETag" to "\"v1\"", "Accept-Ranges" to "bytes"), sendOnly = 1234) }
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a.tar.gz") }.kind)
        assertEquals(1234L, partial.length())
        assertTrue(partialInfo.exists())
        // Never a finished download.
        assertFalse(File(cache, ROOTFS_TARBALL).exists())
    }

    @Test fun anInterruptedDownloadWithoutAValidatorStartsOver() {
        var calls = 0
        val s = server { if (calls++ == 0) Response(200, body, sendOnly = 1234) else Response(200, body) }
        val url = "${s.base}/a.tar.gz"
        // Nothing to continue, so nothing ties the next try to this server.
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download(url) }.kind)
        assertArrayEquals(body, downloader().download(url).readBytes())
        assertNull(s.requests.last().header("Range"))
    }

    @Test fun rejectsOversizedDownloadsAndSignatures() {
        // The mirror's fault, not the tarball's: Network, so another mirror gets a chance.
        val limits = DownloadLimits(maxArchiveBytes = 1000, maxSignatureBytes = 100)
        val s = server { req -> if (req.path.endsWith(".sig")) Response(200, ByteArray(101)) else Response(200, body) }
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader(limits).download("${s.base}/a") }.kind)
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader(limits).signature("${s.base}/a.sig") }.kind)
        val unknown = server { req -> if (req.path.endsWith(".sig")) Response(200, ByteArray(101), contentLength = null) else Response(200, body, contentLength = null) }
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader(limits).download("${unknown.base}/a") }.kind)
        assertFalse(partial.exists())
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader(limits).signature("${unknown.base}/a.sig") }.kind)
    }

    @Test fun reportsHttpErrors() {
        val s = server { Response(404) }
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a") }.kind)
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().signature("${s.base}/a.sig") }.kind)
    }

    @Test fun followsBoundedRedirects() {
        val s = server { req -> if (req.path == "/old") Response(302, headers = mapOf("Location" to "/new")) else Response(200, body) }
        assertArrayEquals(body, downloader().download("${s.base}/old").readBytes())
        val loop = server { Response(302, headers = mapOf("Location" to "/again")) }
        val e = assertThrows(RootfsException::class.java) { downloader().download("${loop.base}/start") }
        assertTrue(e.message!!.contains("redirects"))
        assertEquals(DownloadLimits().maxRedirects + 1, loop.requests.size)
    }

    @Test fun refusesRedirectsToDisallowedAddresses() {
        val s = server { Response(302, headers = mapOf("Location" to "http://example.invalid/a.tar.gz")) }
        val e = assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a") }
        assertTrue(e.message!!.contains("not a secure address"))
    }

    @Test fun productionPolicyIsHttpsOnly() {
        val s = server { Response(200, body) }
        val strict = RootfsDownloader(cache)
        assertThrows(RootfsException::class.java) { strict.download("${s.base}/a") }
        assertThrows(RootfsException::class.java) { strict.signature("${s.base}/a.sig") }
        assertTrue(s.requests.isEmpty())
        assertTrue(ROOTFS_MIRRORS.all { URL(it).protocol == "https" })
    }

    @Test fun rejectsReencodedBodies() {
        val s = server { Response(200, body, mapOf("Content-Encoding" to "gzip")) }
        assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a") }
    }

    @Test fun dropsAnOlderLaunchersPartialDownload() {
        partial.writeBytes(other.copyOf(5000))
        File(cache, "$ROOTFS_TARBALL.version").writeText("\"v1\"")
        val s = server(ranged({ body }))
        assertArrayEquals(body, downloader().download("${s.base}/a").readBytes())
        assertNull(s.requests.single().header("Range"))
        assertFalse(File(cache, "$ROOTFS_TARBALL.version").exists())
    }

    @Test fun aPartialFromAnotherUrlIsNotContinued() {
        val first = server(ranged({ other }, cutFirstAt = 5000))
        assertThrows(RootfsException::class.java) { downloader().download("${first.base}/a") }
        val second = server(ranged({ body }))
        assertArrayEquals(body, downloader().download("${second.base}/a").readBytes())
        assertNull(second.requests.single().header("Range"))
    }

    private val modified = "Wed, 05 Aug 2026 12:41:36 GMT"

    @Test fun anotherMirrorContinuesAPartialWithTheSameLastModifiedAndLength() {
        // Like fl.us (ETag and Last-Modified) and de3 (Last-Modified only).
        val origin = server(ranged({ body }, { "\"6a732f80-493e0\"" }, cutFirstAt = 100_000, modified = modified))
        assertThrows(RootfsException::class.java) { downloader().download("${origin.base}/a") }
        val other = server(ranged({ body }, { null }, modified = modified))
        assertArrayEquals(body, downloader().download("${other.base}/a").readBytes())
        val resume = other.requests.single()
        assertEquals("bytes=100000-", resume.header("Range"))
        assertEquals(modified, resume.header("If-Range"))
    }

    @Test fun anotherMirrorWithADifferentFileStartsOver() {
        val origin = server(ranged({ body }, cutFirstAt = 100_000, modified = modified))
        assertThrows(RootfsException::class.java) { downloader().download("${origin.base}/a") }
        // Another release there: If-Range doesn't match, so the whole new file comes and replaces the part.
        val newer = server(ranged({ other }, { "\"v2\"" }, modified = "Thu, 06 Aug 2026 12:00:00 GMT"))
        assertArrayEquals(other, downloader().download("${newer.base}/a").readBytes())
        // Same Last-Modified but another length: the reply's total gives it away; restarted, never appended.
        File(cache, ROOTFS_TARBALL).delete()
        val again = server(ranged({ body }, cutFirstAt = 100_000, modified = modified))
        assertThrows(RootfsException::class.java) { downloader().download("${again.base}/a") }
        val longer = body + ByteArray(10)
        val liar = server(ranged({ longer }, { null }, modified = modified))
        assertArrayEquals(longer, downloader().download("${liar.base}/a").readBytes())
        assertNull(liar.requests.last().header("Range"))
    }

    @Test fun failingToReachAnotherMirrorKeepsThePartial() {
        val origin = server(ranged({ body }, cutFirstAt = 100_000, modified = modified))
        assertThrows(RootfsException::class.java) { downloader().download("${origin.base}/a") }
        val down = server { Response(503) }
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download("${down.base}/a") }.kind)
        assertEquals(100_000L, partial.length())
        assertEquals("${origin.base}/a", downloader().partialUrl)
    }

    @Test fun aReplyThatCantBeTheTarballLeavesThePartialForAnotherMirror() {
        val origin = server(ranged({ body }, cutFirstAt = 100_000, modified = modified))
        assertThrows(RootfsException::class.java) { downloader().download("${origin.base}/a") }
        val limits = DownloadLimits(maxArchiveBytes = 200_000)
        // Whole files that can't be the tarball: re-encoded, or bigger than one can be.
        val gzipped = server { Response(200, body, mapOf("Content-Encoding" to "gzip", "Last-Modified" to "Thu, 06 Aug 2026 12:00:00 GMT")) }
        val huge = server { Response(200, body, mapOf("Last-Modified" to "Thu, 06 Aug 2026 12:00:00 GMT")) }
        // A reply that doesn't continue the file, then the whole file re-encoded.
        val confused =
            server { req ->
                if (req.header("Range") != null) {
                    Response(206, body, mapOf("Last-Modified" to modified, "Content-Range" to "bytes 0-299999/300000"))
                } else {
                    Response(200, body, mapOf("Content-Encoding" to "br"))
                }
            }
        for ((s, d) in listOf(gzipped to downloader(), huge to downloader(limits), confused to downloader())) {
            assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { d.download("${s.base}/a") }.kind)
            assertEquals(100_000L, partial.length())
            assertEquals("${origin.base}/a", downloader().partialUrl)
        }
        // So a mirror with the same file still continues it.
        val other = server(ranged({ body }, { null }, modified = modified))
        assertArrayEquals(body, downloader().download("${other.base}/a").readBytes())
        assertEquals("bytes=100000-", other.requests.single().header("Range"))
    }

    @Test fun aTransferThatGetsNowhereIsNotWorthComingBackFor() {
        var calls = 0
        val s =
            server { req ->
                when {
                    calls++ == 0 -> Response(200, body, mapOf("ETag" to "\"v1\"", "Accept-Ranges" to "bytes"), sendOnly = 100_000)
                    // The range is accepted, then nothing arrives.
                    else -> Response(206, body.copyOfRange(100_000, body.size), mapOf("ETag" to "\"v1\"", "Content-Range" to "bytes 100000-299999/300000"), sendOnly = 0)
                }
            }
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a") }.kind)
        assertEquals(RootfsException.Kind.Network, assertThrows(RootfsException::class.java) { downloader().download("${s.base}/a") }.kind)
        assertEquals(100_000L, partial.length())
    }

    @Test fun cancellationPropagatesAndKeepsTheResumableState() {
        val s = server(ranged({ body }))
        var reads = 0
        val cancelling = downloader(ensureActive = { if (++reads > 3) throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { cancelling.download("${s.base}/a") }
        assertTrue(partialInfo.exists())
        assertFalse(File(cache, ROOTFS_TARBALL).exists())
        assertArrayEquals(body, downloader().download("${s.base}/a").readBytes())
    }
}
