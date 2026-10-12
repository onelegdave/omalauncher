package dev.omalauncher.linux

import dev.omalauncher.LauncherData
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class RootfsExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var staging: File
    private lateinit var outside: File
    private lateinit var sentinel: File

    @Before fun setUp() {
        staging = tmp.newFolder("arch.staging")
        outside = tmp.newFolder("outside")
        sentinel = File(outside, "sentinel").apply { writeText("untouched") }
    }

    /** Unpacks into a fresh staging directory, as the installer does for every try. */
    private fun unpack(
        gz: ByteArray,
        limits: ExtractLimits = ExtractLimits(),
    ): Int {
        LauncherData.deleteTree(staging)
        check(staging.mkdir())
        return verified(File(tmp.root, "archive-${System.nanoTime()}"), gz).use { RootfsExtractor(limits).extract(it, staging) }
    }

    /** Unpacking fails as a bad archive, and nothing outside staging changed. */
    private fun rejects(
        gz: ByteArray,
        limits: ExtractLimits = ExtractLimits(),
        message: String? = null,
    ) {
        val before = tmp.root.walk().map { it.relativeTo(tmp.root).path }.filterNot { it.startsWith("arch.staging") || it.startsWith("archive-") }.toSet()
        val e = assertThrows(RootfsException::class.java) { unpack(gz, limits) }
        assertEquals(e.message, RootfsException.Kind.Format, e.kind)
        message?.let { assertTrue(e.message, e.message!!.contains(it)) }
        val after = tmp.root.walk().map { it.relativeTo(tmp.root).path }.filterNot { it.startsWith("arch.staging") || it.startsWith("archive-") }.toSet()
        assertEquals(before, after)
        assertEquals("untouched", sentinel.readText())
        // Nothing under staging is a symlink: they're only created after everything else succeeded.
        assertTrue(staging.walk().none { Files.isSymbolicLink(it.toPath()) })
    }

    private fun path(name: String) = File(staging, name).toPath()

    private fun mode(name: String) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path(name), LinkOption.NOFOLLOW_LINKS))

    private fun link(name: String) = Files.readSymbolicLink(path(name)).toString()

    // Compatibility.

    @Test fun unpacksUpstreamsLayout() {
        val gz =
            TarFixture
                .minimalRootfs()
                .symlink("./etc/resolv.conf", "/run/systemd/resolve/resolv.conf")
                .symlink("./etc/os-release", "../usr/lib/os-release")
                .symlink("./etc/mtab", "../proc/self/mounts")
                .symlink("./usr/lib/libkeyutils.so", "/usr/lib/libkeyutils.so.1")
                .file("./usr/lib/os-release", "NAME=Arch")
                .dir("./proc/", 0x16d) // 0555
                .dir("./tmp/", 0x3ff) // 01777
                .file("./usr/bin/passwd", "suid", 0x9ed) // 04755
                .file("./etc/shadow", "secret", 0x180) // 0600
                .file("./usr/bin/readonly", "r", 0x124) // 0444
                .gz()
        unpack(gz)
        assertEquals("usr/bin", link("bin"))
        assertEquals("bash", link("usr/bin/sh"))
        // Absolute guest links keep their text and aren't resolved on the host; dangling ones are fine.
        assertEquals("/run/systemd/resolve/resolv.conf", link("etc/resolv.conf"))
        assertEquals("/usr/lib/libkeyutils.so.1", link("usr/lib/libkeyutils.so"))
        assertEquals("../usr/lib/os-release", link("etc/os-release"))
        assertEquals("../proc/self/mounts", link("etc/mtab"))
        assertEquals("rwxr-xr-x", mode("usr/bin/bash"))
        assertEquals("rwxr-x---", mode("root"))
        assertEquals("rwxr-xr-x", mode("proc"))
        assertEquals("rwxrwxrwx", mode("tmp"))
        // setuid dropped, permission bits kept.
        assertEquals("rwxr-xr-x", mode("usr/bin/passwd"))
        assertEquals("rw-------", mode("etc/shadow"))
        // The owner can always write: the launcher edits pacman.conf and deletes the tree.
        assertEquals("rw-r--r--", mode("usr/bin/readonly"))
        assertEquals("#!bash", File(staging, "usr/bin/bash").readText())
    }

    @Test fun copiesHardLinksIncludingLaterTargetsAndChains() {
        val gz =
            TarFixture
                .minimalRootfs()
                .dir("./usr/share/")
                .file("./usr/share/a", "content", 0x1ed)
                .hardlink("./usr/share/b", "./usr/share/a")
                .hardlink("./usr/share/c", "usr/share/b") // chain, without the ./
                .hardlink("./usr/share/early", "./usr/share/late") // target comes later
                .file("./usr/share/late", "later")
                .symlink("./usr/share/s", "/etc/hostname")
                .hardlink("./usr/share/s2", "./usr/share/s") // a hard link to a symlink is that symlink again
                .gz()
        unpack(gz)
        for (n in listOf("a", "b", "c")) assertEquals("content", File(staging, "usr/share/$n").readText())
        assertEquals("rwxr-xr-x", mode("usr/share/c"))
        assertEquals("later", File(staging, "usr/share/early").readText())
        assertEquals("/etc/hostname", link("usr/share/s2"))
        assertFalse(Files.isSymbolicLink(path("usr/share/b")))
    }

    @Test fun skipsExactlyWhatProotCantUse() {
        val gz =
            TarFixture
                .minimalRootfs()
                .dir("./boot/")
                .file("./boot/Image", "kernel")
                .file("./bootx", "kept")
                .dir("./dev/")
                .special("./dev/null", TarConstants.LF_CHR)
                .file("./dev/stray", "skipped")
                .dir("./usr/lib/firmware/")
                .file("./usr/lib/firmware/blob", "skipped")
                .dir("./usr/lib/modules/")
                .file("./usr/lib/modules/x.ko", "skipped")
                .file("./usr/lib/firmwarex", "kept")
                .special("./run/fifo", TarConstants.LF_FIFO)
                .special("./run/disk", TarConstants.LF_BLK)
                .gz()
        unpack(gz)
        assertFalse(File(staging, "boot").exists())
        assertTrue(File(staging, "bootx").isFile)
        assertTrue(File(staging, "dev").isDirectory)
        assertEquals(0, File(staging, "dev").list()!!.size)
        assertTrue(File(staging, "usr/lib/firmware").isDirectory)
        assertEquals(0, File(staging, "usr/lib/firmware").list()!!.size)
        assertEquals(0, File(staging, "usr/lib/modules").list()!!.size)
        assertTrue(File(staging, "usr/lib/firmwarex").isFile)
        assertTrue(File(staging, "run").isDirectory)
        assertFalse(Files.exists(path("run/fifo"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(path("run/disk"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun handlesLongNames() {
        val deep = "./usr/share/" + (1..12).joinToString("/") { "directory-$it" } + "/" + "f".repeat(200)
        unpack(TarFixture.minimalRootfs().file(deep, "deep").symlink("./usr/share/long-link", "x".repeat(300)).gz())
        assertEquals("deep", File(staging, deep.removePrefix("./")).readText())
        assertEquals("x".repeat(300), link("usr/share/long-link"))
    }

    @Test fun checksTheShapeOnlyAfterEverything() {
        rejects(TarFixture().dir("./").dir("./usr/").dir("./usr/bin/").file("./usr/bin/bash").gz(), message = "doesn't look like")
    }

    // Containment.

    private fun base() = TarFixture.minimalRootfs()

    @Test fun rejectsTraversingAndAbsoluteNames() {
        rejects(base().file("../outside/evil").gz())
        rejects(base().file("./../outside/evil").gz())
        rejects(base().file("usr/../../outside/evil").gz())
        rejects(base().file("${outside.path}/evil").gz())
        rejects(base().file("/outside/evil").gz())
        rejects(base().file("usr//bin/x").gz())
        rejects(base().file("usr/./bin/x").gz())
        // The staging directory's sibling with a shared prefix.
        rejects(base().file("../arch.staging-evil/x").gz())
        assertFalse(File(tmp.root, "arch.staging-evil").exists())
    }

    @Test fun rejectsMalformedNames() {
        fun raw(name: ByteArray) = gzip(rawTar(rawTarHeader(name, size = 1) to byteArrayOf(1)))
        rejects(raw("a\u0001b".toByteArray()))
        rejects(raw(byteArrayOf('a'.code.toByte(), 0xff.toByte(), 0xfe.toByte())))
        rejects(raw("a\nb".toByteArray()))
        rejects(TarFixture.minimalRootfs().file("./usr/share/" + "n".repeat(256)).gz())
        rejects(TarFixture.minimalRootfs().file("./" + (1..900).joinToString("/") { "dd$it" }).gz())
        // A file name with a trailing slash isn't a directory in disguise.
        rejects(gzip(rawTar(rawTarHeader("x/".toByteArray(), size = 1) to byteArrayOf(1))))
    }

    @Test fun rejectsWritesThroughARelativeSymlinkOutOfStaging() {
        val rel = staging.toPath().relativize(outside.toPath()).toString()
        rejects(base().symlink("./escape", rel).file("./escape/evil").gz(), message = "isn't a directory")
        assertFalse(File(outside, "evil").exists())
    }

    @Test fun rejectsWritesThroughAnAbsoluteGuestSymlink() {
        rejects(base().symlink("./host", outside.path).file("./host/evil").gz())
        rejects(base().symlink("./usr/x", "/").file("./usr/x/evil").gz())
        // The real layout: /lib is a symlink; nothing may be put "into" it.
        rejects(base().file("./lib/evil").gz())
        rejects(base().dir("./bin/sub/").gz())
        assertFalse(File(outside, "evil").exists())
    }

    @Test fun rejectsDuplicatesAndTypeChanges() {
        rejects(base().file("./usr/bin/bash", "again").gz(), message = "twice")
        rejects(base().dir("./usr/bin/bash/").gz())
        rejects(base().symlink("./usr", "/tmp").gz())
        rejects(base().file("./x").symlink("./x", "y").gz())
        rejects(base().symlink("./y", "x").file("./y").gz())
        rejects(base().symlink("./z", "x").symlink("./z", "x").gz())
        rejects(base().file("./w").hardlink("./w", "./usr/bin/bash").gz())
        // A directory repeated as a directory is fine: its mode is the last one's.
        unpack(base().dir("./etc/", 0x1c0).gz())
        assertEquals("rwx------", mode("etc"))
    }

    @Test fun rejectsAbusiveHardLinks() {
        rejects(base().hardlink("./h", "../outside/sentinel").gz())
        rejects(base().hardlink("./h", sentinel.path).gz())
        rejects(base().hardlink("./h", "./missing").gz(), message = "isn't in it")
        rejects(base().hardlink("./h", "./usr/bin").gz(), message = "directory")
        rejects(base().hardlink("./h1", "./h2").hardlink("./h2", "./h1").gz(), message = "loop")
        rejects(base().hardlink("./h", "./h").gz(), message = "loop")
        // Its target was skipped, so it isn't there to copy.
        rejects(base().dir("./usr/lib/firmware/").file("./usr/lib/firmware/fw").hardlink("./fw", "./usr/lib/firmware/fw").gz())
        // Through a symlinked directory: never resolved on the host.
        rejects(base().hardlink("./h", "./lib/ld-linux-aarch64.so.1").gz())
    }

    @Test fun aHardLinkToASymlinkNeverCopiesWhatItPointsAt() {
        unpack(base().symlink("./s", sentinel.path).hardlink("./h", "./s").gz())
        assertEquals(sentinel.path, link("h"))
        assertTrue(Files.isSymbolicLink(path("h")))
    }

    @Test fun rejectsUnsupportedEntries() {
        fun raw(type: Char) = gzip(rawTar(rawTarHeader("x".toByteArray(), type = type) to ByteArray(0)))
        rejects(raw('S'), message = "sparse")
        rejects(raw('D'))
        rejects(raw('V'))
        rejects(raw('M'))
        rejects(base().symlink("./empty", "").gz())
    }

    @Test fun enforcesLimits() {
        rejects(base().sized("./big", 4096).gz(), ExtractLimits(maxFileBytes = 4095), "4096 bytes")
        rejects(base().gz(), ExtractLimits(maxEntries = 5), "entries")
        rejects(base().sized("./big", 1 shl 20).gz(), ExtractLimits(maxTarBytes = 1 shl 19), "unpacks to more")
        rejects(base().sized("./a", 3000).sized("./b", 3000).gz(), ExtractLimits(maxWrittenBytes = 5000), "GB of files")
        rejects(base().sized("./a", 3000).hardlink("./b", "./a").hardlink("./c", "./a").gz(), ExtractLimits(maxWrittenBytes = 7000), "GB of files")
        rejects(base().file("./usr/share/" + "a".repeat(31)).gz(), ExtractLimits(maxPathBytes = 40))
        unpack(base().file("./usr/share/" + "a".repeat(30)).gz(), ExtractLimits(maxPathBytes = 40))
        rejects(base().symlink("./l", "x".repeat(40)).gz(), ExtractLimits(maxLinkBytes = 39))
    }

    // Metadata: GNU long names and links, PAX headers. Commons Compress reads each into memory whole.

    /** Raw entries, then a valid rootfs: what's being tested comes first, and everything else checks out. */
    private fun beforeBase(vararg entries: Pair<ByteArray, ByteArray>) = gzip(rawTar(*entries, end = false) + base().tar())

    private val file = rawTarHeader("./usr/share/after".toByteArray(), size = 1) to byteArrayOf(1)

    @Test fun rejectsOversizedMetadataFromItsHeaderBeforeReadingIt() {
        // 400 MB claimed and none of it sent: refused at the header, not by running out of data (or memory).
        for (type in listOf('L', 'K', 'x', 'X', 'g')) {
            rejects(gzip(rawTar(rawTarHeader("././@LongLink".toByteArray(), type, size = 400_000_000) to ByteArray(0), end = false)), message = "metadata record of 400000000 bytes")
        }
        // Base-256 sizes, as GNU tar writes big ones, are read as such: positive and negative.
        val huge = ByteArray(12).also { it[0] = 0x80.toByte(); it[6] = 1 } // 2^40
        rejects(gzip(rawTar(rawTarHeader("x".toByteArray(), 'x', sizeField = huge) to ByteArray(0), end = false)), message = "metadata record of 1099511627776 bytes")
        val negative = ByteArray(12) { 0xff.toByte() }
        rejects(gzip(rawTar(rawTarHeader("x".toByteArray(), 'L', sizeField = negative) to ByteArray(0), end = false)), message = "metadata record of -1 bytes")
        rejects(gzip(rawTar(rawTarHeader("x".toByteArray(), 'x', sizeField = "12x45678901\u0000".toByteArray()) to ByteArray(0), end = false)), message = "malformed size")
        // Just over a smaller limit, with the data really there.
        val pax = pax("comment" to "c".repeat(2000))
        rejects(beforeBase(rawTarHeader("x".toByteArray(), 'x', pax.size.toLong()) to pax, file), ExtractLimits(maxMetadataBytes = pax.size - 1L), "metadata record")
    }

    @Test fun acceptsMetadataUpToTheLimit() {
        val name = "./usr/share/" + "a".repeat(200) + "/" + "b".repeat(200) + "/" + "c".repeat(185)
        val data = (name + "\u0000").toByteArray()
        assertEquals(600, data.size)
        val gz = beforeBase(rawTarHeader("././@LongLink".toByteArray(), 'L', data.size.toLong()) to data, rawTarHeader("short".toByteArray(), size = 1) to byteArrayOf(7))
        unpack(gz, ExtractLimits(maxMetadataBytes = 600))
        assertArrayEquals(byteArrayOf(7), File(staging, name.removePrefix("./")).readBytes())
        rejects(gz, ExtractLimits(maxMetadataBytes = 599), "metadata record of 600 bytes")
    }

    @Test fun boundsMetadataRecordsInARow() {
        // Each record makes Commons Compress nest another call before the entry exists to be counted.
        val record = pax("comment" to "c").let { rawTarHeader("x".toByteArray(), 'x', it.size.toLong()) to it }
        unpack(beforeBase(*Array(4) { record }, file))
        assertArrayEquals(byteArrayOf(1), File(staging, "usr/share/after").readBytes())
        rejects(beforeBase(*Array(5) { record }, file), message = "more than 4 metadata records")
        // Records belonging to different entries don't add up.
        unpack(beforeBase(*Array(3) { record }, file, *Array(3) { record }, rawTarHeader("./usr/share/later".toByteArray(), size = 1) to byteArrayOf(2)))
    }

    @Test fun boundsGlobalMetadataAcrossTheArchive() {
        // Global headers apply to every later entry, so they're all kept: their total is bounded, not each one.
        val global = pax("comment" to "g".repeat(40_000)).let { rawTarHeader("g".toByteArray(), 'g', it.size.toLong()) to it }
        unpack(beforeBase(global, file))
        rejects(beforeBase(global, file, global, rawTarHeader("./usr/share/later".toByteArray(), size = 1) to byteArrayOf(2)), message = "global metadata")
    }

    @Test fun boundsWhatTarReadsBeforeAnEntry() {
        // A PAX 1.0 sparse map is parsed, entry by entry of it, before the entry is returned and refused as sparse.
        val header = pax("GNU.sparse.realsize" to "10", "GNU.sparse.name" to "./usr/share/sparse")
        val map = ("99999999\n" + "0\n0\n".repeat(300_000)).toByteArray()
        rejects(
            beforeBase(rawTarHeader("x".toByteArray(), 'x', header.size.toLong()) to header, rawTarHeader("./GNUSparseFile.0/sparse".toByteArray(), size = map.size.toLong()) to map),
            message = "more metadata before an entry",
        )
    }

    @Test fun boundsTheNamesKeptInMemory() {
        rejects(base().gz(), ExtractLimits(maxNameChars = 100), "characters of names")
        unpack(base().gz(), ExtractLimits(maxNameChars = 3000))
        // Link texts count too.
        rejects(base().symlink("./usr/share/l", "x".repeat(4000)).gz(), ExtractLimits(maxNameChars = 3000), "characters of names")
    }

    @Test fun reservesTheActivationMarker() {
        rejects(base().file("./$ROOTFS_TOKEN", "0123456789abcdef0123456789abcdef").gz(), message = "reserves")
    }

    // Complete archive validation.

    private val good by lazy { base().file("./usr/share/data", "x".repeat(50_000)).tar() }

    @Test fun rejectsTruncatedTar() {
        rejects(gzip(good.copyOf(good.size - 1024 - 512 * 50)))
        // Cut between entries: no end-of-archive marker.
        rejects(gzip(rawTar(rawTarHeader("./x".toByteArray(), size = 1) to byteArrayOf(1), end = false)), message = "end-of-archive")
    }

    @Test fun rejectsDamagedGzip() {
        val gz = gzip(good)
        // Truncated: no trailer.
        rejects(gz.copyOf(gz.size - 4))
        rejects(gz.copyOf(gz.size / 2))
        // A wrong CRC in the trailer, reached only after tar's end marker.
        rejects(gz.copyOf().also { it[it.size - 6] = (it[it.size - 6] + 1).toByte() })
        // Damage in the deflate data.
        rejects(gz.copyOf().also { it[gz.size / 2] = (it[gz.size / 2].toInt() xor 0x55).toByte() })
        rejects("not gzip at all".toByteArray())
    }

    @Test fun rejectsAnythingAfterTheArchive() {
        val gz = gzip(good)
        rejects(gz + "trailing".toByteArray(), message = "after its gzip stream")
        // A second gzip member is not part of what's unpacked.
        rejects(gz + gzip(base().file("./extra").tar()), message = "after its gzip stream")
        // Data after tar's end-of-archive records, inside the gzip stream.
        rejects(gzip(good + "more".toByteArray()), message = "after its end")
        // A lone zero record, then a valid header: tar would read the header as its second end record and drop it.
        val first = rawTar(rawTarHeader("./a".toByteArray(), size = 1) to byteArrayOf(1), end = false)
        val hidden = rawTar(rawTarHeader("./b".toByteArray(), size = 1) to byteArrayOf(1), end = false)
        rejects(gzip(first + ByteArray(512) + hidden + ByteArray(1024)), message = "after the end-of-archive marker")
        // Zero padding is normal.
        unpack(gzip(good + ByteArray(10240)))
    }

    @Test fun rejectsACorruptTarHeader() {
        val tar = good.copyOf().also { it[148] = '7'.code.toByte() } // the first header's checksum
        rejects(gzip(tar))
    }

    @Test fun detectsTheVerifiedFileChangingInPlace() {
        val gz = gzip(good)
        val file = File(tmp.root, "archive-inplace")
        verified(file, gz).use { v ->
            // gzip's MTIME field: still a valid gzip stream with the same tar inside, but not the verified bytes.
            java.io.RandomAccessFile(file, "rw").use {
                it.seek(4)
                it.write(0x7f)
            }
            val e = assertThrows(RootfsException::class.java) { RootfsExtractor().extract(v, staging) }
            assertEquals(RootfsException.Kind.Changed, e.kind)
            assertTrue(e.message, e.message!!.contains("changed while it was unpacked"))
        }
    }

    @Test fun refusesAStagingDirectoryThatIsALinkOrNotEmpty() {
        val link = File(tmp.root, "linked-staging").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        verified(File(tmp.root, "archive-l"), base().gz()).use { v ->
            assertThrows(IllegalArgumentException::class.java) { RootfsExtractor().extract(v, link.toFile()) }
            File(staging, "leftover").writeText("x")
            assertThrows(IllegalArgumentException::class.java) { RootfsExtractor().extract(v, staging) }
        }
        assertEquals(listOf("sentinel"), outside.list()!!.toList())
    }

    @Test fun deletingAnUnpackedTreeNeverFollowsItsLinks() {
        unpack(base().symlink("./host-dir", outside.path).symlink("./host-file", sentinel.path).gz())
        LauncherData.deleteTree(staging)
        assertFalse(staging.exists())
        assertEquals("untouched", sentinel.readText())
    }

    @Test fun memberNamesNormalizeOnlyTheHarmlessParts() {
        assertEquals("", memberPath("./", directory = true))
        assertEquals("", memberPath(".", directory = true))
        assertEquals("usr/bin", memberPath("./usr/bin/", directory = true))
        assertEquals("usr/bin", memberPath("usr/bin", directory = false))
        assertNull(memberPath("usr/bin/", directory = false))
        assertNull(memberPath("", directory = false))
        assertNull(memberPath("/usr", directory = true))
        assertNull(memberPath(".//usr", directory = true))
        assertNull(memberPath("././usr", directory = true))
        assertNull(memberPath("usr/..", directory = true))
        assertNull(memberPath("a\u0000b", directory = false))
        assertTrue(linkTextOk("/usr/lib/../lib/x"))
        assertTrue(linkTextOk("../../.."))
        assertFalse(linkTextOk(""))
        assertFalse(linkTextOk("a\u0000b"))
    }

    @Test fun keepsFileContentsByteForByte() {
        unpack(TarFixture.minimalRootfs().sized("./usr/share/zeros", 70_000).file("./usr/share/text", "é ünïcode\n").gz())
        assertArrayEquals(ByteArray(70_000), File(staging, "usr/share/zeros").readBytes())
        assertEquals("é ünïcode\n", File(staging, "usr/share/text").readText())
    }
}
