package dev.omalauncher.linux

import dev.omalauncher.linux.RawHttpServer.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsSourceTest {
    @Test fun insufficientSpaceStopsBeforeDownloadingOrTryingAnotherMirror() {
        val a = mirror(listOf(v1), listOf(sig1))
        val b = mirror(listOf(v1), listOf(sig1))
        val noSpace = java.io.IOException("ENOSPC")
        val source = RootfsSource(
            cache, null, signer.verifier(), RootfsDownloader(cache, allowed = { it.host == "127.0.0.1" }),
            listOf(a.base + "/os/", b.base + "/os/"), beforeDownload = { throw noSpace },
        )
        assertTrue(assertThrows(java.io.IOException::class.java) { source.obtain() } === noSpace)
        assertEquals(0, archiveRequests(a))
        assertTrue(b.requests.isEmpty())
    }

    @Test fun insufficientSpaceForAPushedCopyPreservesTheExternalInput() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(sig1)
        val source = RootfsSource(
            cache, external, signer.verifier(), RootfsDownloader(cache),
            beforeCopy = { bytes -> assertEquals(v1.size.toLong(), bytes); throw java.io.IOException("ENOSPC") },
        )
        assertThrows(java.io.IOException::class.java) { source.obtain() }
        assertArrayEquals(v1, File(external, ROOTFS_TARBALL).readBytes())
        assertFalse(source.snapshot.exists())
    }

    @Test fun cancellationDuringAPushedCopyDropsOnlyThePrivateSnapshot() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(sig1)
        var checks = 0
        val source = RootfsSource(
            cache, external, signer.verifier(), RootfsDownloader(cache),
            ensureActive = { if (++checks > 2) throw java.util.concurrent.CancellationException("stop") },
        )
        assertThrows(java.util.concurrent.CancellationException::class.java) { source.obtain() }
        assertFalse(source.snapshot.exists())
        assertArrayEquals(v1, File(external, ROOTFS_TARBALL).readBytes())
    }

    @get:Rule val tmp = TemporaryFolder()

    private val signer = TestSigner.trusted
    private val v1 = ByteArray(200_000) { (it % 253).toByte() }
    private val v2 = ByteArray(210_000) { (it % 241).toByte() }
    private val sig1 by lazy { signer.sign(v1) }
    private val sig2 by lazy { signer.sign(v2) }

    private val cache by lazy { tmp.newFolder("cache") }
    private val external by lazy { tmp.newFolder("external") }
    private val servers = ArrayList<RawHttpServer>()

    @After fun stop() = servers.forEach { it.close() }

    /** A mirror serving [archive] and [sig] for each request in turn (the last one repeats). */
    private fun mirror(
        archives: List<ByteArray>,
        sigs: List<ByteArray>,
    ): RawHttpServer {
        var a = 0
        var s = 0
        return RawHttpServer { req ->
            when {
                req.path.endsWith("$ROOTFS_TARBALL.sig") -> Response(200, sigs[minOf(s++, sigs.size - 1)])
                req.path.endsWith(ROOTFS_TARBALL) -> Response(200, archives[minOf(a++, archives.size - 1)], mapOf("ETag" to "\"x\""))
                else -> Response(404)
            }
        }.also { servers += it }
    }

    private fun source(
        vararg mirrors: RawHttpServer,
        bases: List<String> = mirrors.map { it.base + "/os/" },
        pushedDir: File? = null,
        limits: DownloadLimits = DownloadLimits(),
        maxPushedBytes: Long = MAX_ARCHIVE_BYTES,
    ) = RootfsSource(
        cache,
        pushedDir,
        signer.verifier(),
        RootfsDownloader(cache, limits, allowed = { it.host == "127.0.0.1" }),
        bases,
        maxPushedBytes = maxPushedBytes,
    )

    private val modified = "Wed, 05 Aug 2026 12:41:36 GMT"

    /**
     * A mirror serving [archive] like nginx (ranges, If-Range with its ETag or Last-Modified) and
     * [sig]. Each tarball request takes the next of [cuts], an offset it stops sending at.
     */
    private fun ranged(
        archive: ByteArray = v1,
        sig: ByteArray = sig1,
        etag: String? = null,
        cuts: MutableList<Int> = ArrayList(),
    ) = RawHttpServer { req ->
        val headers = listOfNotNull(etag?.let { "ETag" to it }, "Last-Modified" to modified, "Accept-Ranges" to "bytes").toMap()
        val range = req.header("Range")
        val start = if (range != null && req.header("If-Range").let { it == etag || it == modified }) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        val cut = if (req.path.endsWith(ROOTFS_TARBALL)) cuts.removeFirstOrNull() else null
        val send = cut?.let { it - start }
        when {
            req.path.endsWith(".sig") -> Response(200, sig)
            !req.path.endsWith(ROOTFS_TARBALL) -> Response(404)
            start > 0 -> Response(206, archive.copyOfRange(start, archive.size), headers + ("Content-Range" to "bytes $start-${archive.size - 1}/${archive.size}"), sendOnly = send)
            else -> Response(200, archive, headers, sendOnly = send)
        }
    }.also { servers += it }

    private fun ranges(server: RawHttpServer) = server.requests.filter { it.path.endsWith(ROOTFS_TARBALL) }.map { it.header("Range") }

    private fun archiveRequests(server: RawHttpServer) = server.requests.count { it.path.endsWith(ROOTFS_TARBALL) }

    @Test fun downloadsAndVerifiesAPair() {
        val m = mirror(listOf(v1), listOf(sig1))
        source(m).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
    }

    @Test fun reverifiesAFinishedDownloadInsteadOfTrustingIt() {
        File(cache, ROOTFS_TARBALL).writeBytes(v1)
        val m = mirror(listOf(v1), listOf(sig1))
        source(m).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(0, archiveRequests(m))
        assertEquals(1, m.requests.size)
    }

    @Test fun aCachedFileChangedAfterAnEarlierCheckIsReplaced() {
        val m = mirror(listOf(v1), listOf(sig1))
        source(m).obtain().close()
        // An earlier try verified it, then left it in the cache, where it changed.
        File(cache, ROOTFS_TARBALL).writeBytes(v1.copyOf().also { it[10] = 99 })
        source(m).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(2, archiveRequests(m))
    }

    @Test fun latestMovingBetweenSignatureAndTarballGetsAFreshSignature() {
        // The signature is still v1's, the tarball already v2; the second signature request sees v2's.
        val m = mirror(listOf(v2), listOf(sig1, sig2))
        source(m).obtain().use { assertArrayEquals(v2, it.open().readBytes()) }
        assertEquals(1, archiveRequests(m))
    }

    @Test fun aStaleCachedTarballIsReplacedByAFreshPair() {
        File(cache, ROOTFS_TARBALL).writeBytes(v1)
        val m = mirror(listOf(v2), listOf(sig2))
        source(m).obtain().use { assertArrayEquals(v2, it.open().readBytes()) }
        assertEquals(1, archiveRequests(m))
    }

    @Test fun aPersistentMismatchFailsWithoutAnUnsignedFallback() {
        // A well-formed signature by the right key passes the early check, but the bytes must still match it.
        val tampered = v1.copyOf().also { it[5] = 1 }
        val m = mirror(listOf(tampered), listOf(sig1))
        repeat(2) { n ->
            val e = assertThrows(RootfsException::class.java) { source(m).obtain() }
            assertEquals(RootfsException.Kind.Mismatch, e.kind)
            assertFalse(e.kind.retryable)
            // One whole copy per try from the only mirror there is; the bad bytes aren't kept for the next try.
            assertEquals(n + 1, archiveRequests(m))
            assertFalse(File(cache, ROOTFS_TARBALL).exists())
        }
    }

    @Test fun aMirrorWithAMismatchedPairGivesWayToAHealthyOne() {
        // A keeps serving a tarball its own signature doesn't fit (a broken sync, say); B is fine.
        val tampered = v1.copyOf().also { it[5] = 1 }
        val a = mirror(listOf(tampered), listOf(sig1))
        val b = mirror(listOf(v1), listOf(sig1))
        source(a, b).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        // In the same try: one copy from A, then the fresh pair from B.
        assertEquals(1, archiveRequests(a))
        assertEquals(1, archiveRequests(b))
        // A later try (say the unpacking failed) asks B first; A isn't asked for anything.
        val asked = a.requests.size
        File(cache, ROOTFS_TARBALL).delete()
        source(a, b).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(asked, a.requests.size)
        assertEquals(2, archiveRequests(b))
    }

    @Test fun mismatchedPairsEverywhereStopAfterTwoDownloadsAndAskTheOthersFirstNextTime() {
        val tampered = v1.copyOf().also { it[5] = 1 }
        val a = mirror(listOf(tampered), listOf(sig1))
        val b = mirror(listOf(tampered), listOf(sig1))
        val c = mirror(listOf(tampered), listOf(sig1))
        val e = assertThrows(RootfsException::class.java) { source(a, b, c).obtain() }
        // Not retried by the step: that would download again.
        assertEquals(RootfsException.Kind.Mismatch, e.kind)
        assertEquals(listOf(1, 1, 0), listOf(a, b, c).map(::archiveRequests))
        // The next try starts with the mirror that hasn't sent a mismatched pair; still two at most.
        assertThrows(RootfsException::class.java) { source(a, b, c).obtain() }
        assertEquals(listOf(2, 1, 1), listOf(a, b, c).map(::archiveRequests))
        assertTrue(c.requests.first().path.endsWith(".sig"))
    }

    @Test fun aMismatchAfterContinuingAnotherMirrorsFileGetsAFreshCopyThere() {
        val a = ranged(etag = "\"a\"", cuts = mutableListOf(100_000))
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { source(a).obtain() }.kind)
        a.close()
        // B has another file with the same Last-Modified and length: continuing A's bytes there makes a splice.
        val w = ByteArray(v1.size) { (it % 239).toByte() }
        val b = ranged(archive = w, sig = signer.sign(w))
        source(a, b).obtain().use { assertArrayEquals(w, it.open().readBytes()) }
        // The splice matched nothing; B's whole file did. B isn't held against it.
        assertEquals(listOf("bytes=100000-", null), ranges(b))
        assertFalse(File(cache, "$ROOTFS_TARBALL.mismatched").exists())
    }

    @Test fun aSignatureOutageKeepsTheFinishedTarball() {
        File(cache, ROOTFS_TARBALL).writeBytes(v1)
        val down = RawHttpServer { Response(503) }.also { servers += it }
        val e = assertThrows(RootfsException::class.java) { source(down).obtain() }
        assertEquals(RootfsException.Kind.Network, e.kind)
        assertTrue(File(cache, ROOTFS_TARBALL).exists())
        val m = mirror(listOf(v1), listOf(sig1))
        source(m).obtain().close()
        assertEquals(0, archiveRequests(m))
    }

    @Test fun aGarbledSignatureIsRetryableAndKeepsTheTarball() {
        File(cache, ROOTFS_TARBALL).writeBytes(v1)
        val m = mirror(listOf(v1), listOf("<html>log in to the wifi</html>".toByteArray()))
        val e = assertThrows(RootfsException::class.java) { source(m).obtain() }
        assertEquals(RootfsException.Kind.Network, e.kind)
        assertTrue(File(cache, ROOTFS_TARBALL).exists())
    }

    @Test fun aSignatureThatCantBeAcceptedCostsNoTarballDownload() {
        val garbled = mirror(listOf(v1), listOf("<html>log in to the wifi</html>".toByteArray()))
        val wrongKey = mirror(listOf(v1), listOf(TestSigner.other.sign(v1)))
        val right = mirror(listOf(v1), listOf(sig1))
        source(garbled, wrongKey, right).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(0, archiveRequests(garbled))
        assertEquals(0, archiveRequests(wrongKey))
        assertEquals(1, archiveRequests(right))
    }

    @Test fun anotherKeysSignatureIsNeverAccepted() {
        val m = mirror(listOf(v1), listOf(TestSigner.other.sign(v1)))
        assertEquals(RootfsException.Kind.Signer, assertThrows(RootfsException::class.java) { source(m).obtain() }.kind)
    }

    @Test fun triesTheNextMirror() {
        val down = RawHttpServer { Response(500) }.also { servers += it }
        val m = mirror(listOf(v1), listOf(sig1))
        source(down, m).obtain().close()
        assertTrue(down.requests.isNotEmpty())
    }

    @Test fun anInterruptedTransferIsContinuedAtItsMirrorNotRestartedAtTheNext() {
        // Three healthy mirrors from the first try on; the first one's transfer breaks off.
        val a = ranged(etag = "\"a\"", cuts = mutableListOf(120_000))
        val b = ranged()
        val c = ranged()
        val e = assertThrows(RootfsException::class.java) { source(a, b, c).obtain() }
        assertEquals(RootfsException.Kind.Interrupted, e.kind)
        assertTrue(e.kind.retryable)
        // The step's next try continues at the same mirror; the others weren't asked for anything.
        assertTrue(b.requests.isEmpty() && c.requests.isEmpty())
        source(a, b, c).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(listOf(null, "bytes=120000-"), ranges(a))
        assertTrue(b.requests.isEmpty() && c.requests.isEmpty())
    }

    @Test fun anOutageAfterRepeatedInterruptionsFallsOverAndKeepsTheBytes() {
        val a = ranged(etag = "\"a\"", cuts = mutableListOf(100_000, 150_000))
        val b = ranged()
        val c = ranged()
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { source(a, b, c).obtain() }.kind)
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { source(a, b, c).obtain() }.kind)
        assertEquals(listOf(null, "bytes=100000-"), ranges(a))
        // Now the mirror is gone: the next one continues the file (same Last-Modified and length) instead of starting over.
        a.close()
        source(a, b, c).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(listOf("bytes=150000-"), ranges(b))
        assertTrue(c.requests.isEmpty())
    }

    @Test fun aMirrorThatIgnoresRangesAndKeepsBreakingOffGivesWayInTheSameTry() {
        // A claims ranges, ignores them (always the whole file), and breaks off at 120 KB every time.
        val a =
            RawHttpServer { req ->
                val headers = mapOf("ETag" to "\"a\"", "Last-Modified" to modified, "Accept-Ranges" to "bytes")
                if (req.path.endsWith(".sig")) Response(200, sig1) else Response(200, v1, headers, sendOnly = 120_000)
            }.also { servers += it }
        val b = ranged()
        // The first cut looks like one A can continue, as before.
        assertEquals(RootfsException.Kind.Interrupted, assertThrows(RootfsException::class.java) { source(a, b).obtain() }.kind)
        assertTrue(b.requests.isEmpty())
        // Asked to continue, A starts over and breaks off again: B continues A's bytes in the same try.
        source(a, b).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(listOf(null, "bytes=120000-"), ranges(a))
        assertEquals(listOf("bytes=120000-"), ranges(b))
    }

    @Test fun aTransferThatGetsNowhereFallsOverInTheSameTry() {
        // The mirror accepts the connection, then sends nothing: not progress, so not a reason to come back.
        val a = ranged(etag = "\"a\"", cuts = mutableListOf(0))
        val b = ranged()
        source(a, b).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertEquals(listOf<String?>(null), ranges(b))
    }

    @Test fun aMirrorSendingTooMuchGivesWayToTheNext() {
        val limits = DownloadLimits(maxArchiveBytes = 205_000)
        for (length in listOf<Long?>(300_000, null)) {
            File(cache, ROOTFS_TARBALL).delete()
            val big = RawHttpServer { req -> if (req.path.endsWith(".sig")) Response(200, sig1) else Response(200, ByteArray(300_000), contentLength = length) }.also { servers += it }
            val m = mirror(listOf(v1), listOf(sig1))
            source(big, m, limits = limits).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
            assertEquals(1, archiveRequests(big))
            assertEquals(1, archiveRequests(m))
        }
    }

    @Test fun aRefusedSignerIsTriedElsewhereButNeverTrusted() {
        val wrong = mirror(listOf(v1), listOf(TestSigner.other.sign(v1)))
        val right = mirror(listOf(v1), listOf(sig1))
        source(wrong, right).obtain().use { assertArrayEquals(v1, it.open().readBytes()) }
        assertTrue(wrong.requests.isNotEmpty())
    }

    @Test fun aRefusedSignerEverywhereSaysTheAppMayNeedAnUpdate() {
        val down = RawHttpServer { Response(503) }.also { servers += it }
        val rotated = mirror(listOf(v1), listOf(TestSigner.other.sign(v1)))
        val e = assertThrows(RootfsException::class.java) { source(rotated, down).obtain() }
        assertEquals(RootfsException.Kind.Signer, e.kind)
        assertFalse(e.kind.retryable)
        assertTrue(e.message, e.message!!.contains("the app needs an update"))
        // Found before downloading the tarball, and no other key's signature was ever accepted.
        assertEquals(0, archiveRequests(rotated))
        assertFalse(File(cache, ROOTFS_TARBALL).exists())
    }

    // A tarball pushed with adb.

    @Test fun aPushedPairIsVerifiedFromAPrivateCopy() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(sig1)
        val src = source(pushedDir = external, bases = listOf("https://unreachable.invalid/"))
        src.obtain().use { verified ->
            assertTrue(src.snapshot.exists())
            // Replacing the pushed file now changes nothing that gets unpacked.
            File(external, ROOTFS_TARBALL).writeBytes(v2)
            assertArrayEquals(v1, verified.open().readBytes())
        }
        src.clear()
        assertFalse(src.snapshot.exists())
        assertTrue(File(external, ROOTFS_TARBALL).exists())
        assertTrue(File(external, "$ROOTFS_TARBALL.sig").exists())
    }

    @Test fun aPushedTarballWithoutItsSignatureExplainsWhatsMissing() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        val m = mirror(listOf(v1), listOf(sig1))
        val e = assertThrows(RootfsException::class.java) { source(m, pushedDir = external).obtain() }
        assertEquals(RootfsException.Kind.MissingSignature, e.kind)
        assertTrue(e.message!!.contains("$ROOTFS_TARBALL.sig"))
        // No quiet fallback to the network, and the user's file stays.
        assertTrue(m.requests.isEmpty())
        assertArrayEquals(v1, File(external, ROOTFS_TARBALL).readBytes())
    }

    @Test fun aPushedPairThatDoesntMatchFailsAndKeepsTheUsersFiles() {
        File(external, ROOTFS_TARBALL).writeBytes(v2)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(sig1)
        val m = mirror(listOf(v1), listOf(sig1))
        val src = source(m, pushedDir = external)
        val e = assertThrows(RootfsException::class.java) { src.obtain() }
        assertEquals(RootfsException.Kind.Mismatch, e.kind)
        assertTrue(m.requests.isEmpty())
        assertFalse(src.snapshot.exists())
        assertTrue(File(external, ROOTFS_TARBALL).exists())
    }

    @Test fun aPushedSignatureByAnotherKeyFails() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(TestSigner.other.sign(v1))
        val steps = ArrayList<String>()
        val src =
            RootfsSource(cache, external, signer.verifier(), RootfsDownloader(cache, allowed = { false }), emptyList()) { step, _ -> steps += step }
        val e = assertThrows(RootfsException::class.java) { src.obtain() }
        assertEquals(RootfsException.Kind.Signer, e.kind)
        assertTrue(e.message, e.message!!.contains("the app needs an update"))
        // Refused before the tarball was copied.
        assertTrue(steps.isEmpty())
    }

    @Test fun aGarbledPushedSignatureSaysSo() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes("not a signature".toByteArray())
        val e = assertThrows(RootfsException::class.java) { source(pushedDir = external, bases = emptyList()).obtain() }
        assertEquals(RootfsException.Kind.Signature, e.kind)
        assertTrue(e.message, e.message!!.contains("isn't a readable signature"))
    }

    @Test fun anOversizedPushedTarballIsTheUsersToFix() {
        File(external, ROOTFS_TARBALL).writeBytes(v1)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(sig1)
        val src = source(pushedDir = external, bases = emptyList(), maxPushedBytes = 1000)
        val e = assertThrows(RootfsException::class.java) { src.obtain() }
        // Not a network problem another mirror could solve: it's about the user's file, which stays.
        assertEquals(RootfsException.Kind.Format, e.kind)
        assertTrue(e.message, e.message!!.contains("push the one from archlinuxarm.org"))
        assertFalse(src.snapshot.exists())
        assertArrayEquals(v1, File(external, ROOTFS_TARBALL).readBytes())
    }
}
