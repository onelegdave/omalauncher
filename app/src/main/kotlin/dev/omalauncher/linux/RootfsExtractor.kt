package dev.omalauncher.linux

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.tar.TarUtils
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Bounds on what unpacking may produce and hold in memory. The 2026-08-05 rootfs had 49,104
 * entries, 2.15 GB of tar (most of it the firmware and kernel modules we skip), 0.73 GB of files
 * we keep, 1.7 million characters of names and link texts we keep, and seven PAX headers of at
 * most 205 bytes, never more than one before an entry (no GNU long names, no global headers).
 */
class ExtractLimits(
    val maxEntries: Int = 250_000,
    /** The whole uncompressed tar stream, skipped entries included. */
    val maxTarBytes: Long = 8L shl 30,
    /** What's written to the phone, hard-link copies included. */
    val maxWrittenBytes: Long = 4L shl 30,
    val maxFileBytes: Long = 1L shl 30,
    val maxPathBytes: Int = 4095,
    val maxNameBytes: Int = 255,
    val maxLinkBytes: Int = 4095,
    val maxHardLinkChain: Int = 16,
    /** Zero padding allowed after tar's end-of-archive marker. */
    val maxTrailingBytes: Long = 1L shl 20,
    /** One GNU long name or link, or one PAX header (local or global, all together): read into memory whole. */
    val maxMetadataBytes: Long = 64L shl 10,
    /** Metadata records before one entry; each nests a call in the tar reader. */
    val maxMetadataRecords: Int = 4,
    /**
     * What the tar reader may consume to produce one entry: the metadata records and its header,
     * plus the previous entry's padding and the end marker. Bounds what it parses (a PAX 1.0 sparse
     * map, say) before the entry exists to be counted or refused.
     */
    val maxHeaderBytes: Long = (maxMetadataRecords + 1) * (maxMetadataBytes + 1024) + 2048,
    /** Characters of member names and link texts kept in memory until the end. */
    val maxNameChars: Long = 16L shl 20,
)

/**
 * Unpacks a verified rootfs tarball into an empty staging directory, writing nothing outside it.
 *
 * Every member name is checked before anything else happens to it: relative, no "." or ".."
 * components, no empty or oversized ones, valid UTF-8 without control characters. A name is
 * never rewritten into a different valid one; the archive is rejected instead. Each entry's
 * parents must be directories of the archive (or ones it implies); a name under a symlink, a
 * repeated name (other than a directory) or a type change rejects the archive too. Upstream's
 * tarball has none of these.
 *
 * Symlinks are created last, after every file, directory and hard-link copy exists, so no
 * write, chmod or copy can pass through one. Their text is stored exactly as in the archive:
 * absolute targets like /usr/lib/... name guest paths, which proot resolves inside the rootfs,
 * and dangling ones into /proc or /run are normal. Nothing here ever resolves them.
 *
 * Hard links become copies, since app storage doesn't allow hard links. They're resolved after
 * the whole archive was read, inside the archive's own entries only, so a link may name a later
 * entry; a missing target, a directory or a loop rejects the archive.
 *
 * Device nodes and FIFOs are skipped, as are /boot, /dev's contents, firmware and kernel
 * modules, none of which work under proot. Modes keep their permission bits, with the owner
 * always able to read and write (and enter directories) so the launcher can update and delete
 * the tree; setuid, setgid, sticky bits and ownership are dropped.
 *
 * The staging directory is app-private and nothing else writes to it while this runs: no guest
 * process runs before the rootfs is activated. That's the concurrency assumption of the checks.
 */
class RootfsExtractor(
    private val limits: ExtractLimits = ExtractLimits(),
    /** Creates a symlink at path holding exactly target; Android's Os.symlink in the app. */
    private val symlink: (target: String, path: String) -> Unit = { target, path -> Files.createSymbolicLink(Paths.get(path), Paths.get(target)) },
    private val ensureActive: () -> Unit = {},
    private val progress: (entries: Int, fraction: Float) -> Unit = { _, _ -> },
    /** Top-level names the archive mustn't contain: the app's own markers. */
    private val reserved: Set<String> = setOf(ROOTFS_TOKEN),
) {
    /** Unpacks [archive] into [staging], which must be an empty real directory. Returns the number of entries kept. */
    fun extract(
        archive: VerifiedRootfs,
        staging: File,
    ): Int {
        val root = staging.toPath()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "the staging directory is missing or a link" }
        Files.newDirectoryStream(root).use { require(!it.iterator().hasNext()) { "the staging directory isn't empty" } }
        return Unpack(root).run(archive)
    }

    private sealed interface Node

    private class Dir(
        var mode: Int,
    ) : Node

    private class Regular(
        val mode: Int,
    ) : Node

    private class Symlink(
        val target: String,
    ) : Node

    private class HardLink(
        val target: String,
    ) : Node

    private object Special : Node

    private inner class Unpack(
        private val root: Path,
    ) {
        /** Every kept name (relative, normalized) and what the archive made it. */
        private val nodes = HashMap<String, Node>()
        private val hardLinks = ArrayList<String>()
        private val symlinks = ArrayList<Pair<String, String>>()
        private val buf = ByteArray(1 shl 16)
        private var written = 0L
        private var nameChars = 0L

        fun run(archive: VerifiedRootfs): Int {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val compressed = Counting(DigestInputStream(archive.open(), sha256), Long.MAX_VALUE) { error("unreachable") }
            val buffered = BufferedInputStream(compressed, 1 shl 16)
            var entries = 0
            try {
                // One gzip member only; it leaves [buffered] right after its trailer, so anything after it shows.
                val gzip = GzipCompressorInputStream.builder().setInputStream(buffered).setDecompressConcatenated(false).get()
                val plain = Counting(gzip, limits.maxTarBytes) { format("unpacks to more than ${limits.maxTarBytes shr 30} GB") }
                val tar = StrictTar(plain, limits)
                while (true) {
                    val entry = tar.next() ?: break
                    if (++entries > limits.maxEntries) throw format("has more than ${limits.maxEntries} entries")
                    if (entries % 256 == 0) ensureActive()
                    add(entry, tar)
                    if (entries % 500 == 0) progress(entries, compressed.count.toFloat() / archive.length)
                }
                if (!tar.sawEnd) throw format("ends without tar's end-of-archive marker")
                // Tar stops reading at its end marker. Read on, so gzip checks its CRC and length, and nothing may follow.
                var trailing = 0L
                while (true) {
                    val b = plain.read()
                    if (b < 0) break
                    if (b != 0 || ++trailing > limits.maxTrailingBytes) throw format("has data after its end")
                }
                if (buffered.read() >= 0) throw format("has data after its gzip stream")
            } catch (e: RootfsException) {
                throw e
            } catch (e: IOException) {
                throw format("is damaged (${e.message})", e)
            } catch (e: IllegalArgumentException) {
                throw format("is damaged (${e.message})", e)
            }
            // The archive is read through a handle on the verified file; this catches a change of its bytes in place.
            if (compressed.count != archive.length || !archive.matches(sha256.digest())) {
                throw RootfsException(RootfsException.Kind.Changed, "the tarball changed while it was unpacked; try again to fetch it anew")
            }
            ensureActive()
            copyHardLinks()
            ensureActive()
            for ((path, target) in symlinks) {
                storage("can't create a symlink") { symlink(target, resolve(path).toString()) }
            }
            checkShape()
            return nodes.size
        }

        private fun add(
            entry: TarArchiveEntry,
            input: InputStream,
        ) {
            if (entry.isSparse) throw format("has a sparse file, which isn't supported")
            val flag = entry.linkFlag
            val type =
                when (flag) {
                    TarConstants.LF_DIR -> 'd'
                    TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_CONTIG -> 'f'
                    TarConstants.LF_SYMLINK -> 'l'
                    TarConstants.LF_LINK -> 'h'
                    TarConstants.LF_CHR, TarConstants.LF_BLK, TarConstants.LF_FIFO -> 's'
                    else -> throw format("has an entry of unsupported type ${flag.toInt().toChar()}")
                }
            val name = memberPath(entry.name, directory = type == 'd', limits) ?: throw format("has an entry with an unsafe name")
            // Hard-link and symlink targets are checked even when the entry is skipped.
            val hardTarget =
                if (type == 'h') {
                    memberPath(entry.linkName, directory = false, limits)?.takeIf { it.isNotEmpty() } ?: throw format("has a hard link with an unsafe target")
                } else {
                    null
                }
            if (type == 'l' && !linkTextOk(entry.linkName, limits)) throw format("has a symlink with an unusable target")
            if (name.isEmpty()) {
                // "./", the rootfs itself: the staging directory already is it.
                if (type != 'd') throw format("has a non-directory for its root")
                return
            }
            if (name.substringBefore('/') in reserved) throw format("contains a name the launcher reserves")
            if (skipped(name)) return
            if (type == 'f' && (entry.size < 0 || entry.size > limits.maxFileBytes)) throw format("has a file of ${entry.size} bytes")
            ensureParents(name)
            val existing = nodes[name]
            if (existing != null && !(type == 'd' && existing is Dir)) throw format("has $name twice, or as two kinds of file")
            val path = resolve(name)
            when (type) {
                'd' ->
                    if (existing is Dir) {
                        existing.mode = entry.mode
                        chmod(path, dirMode(entry.mode))
                    } else {
                        mkdir(name, path, entry.mode)
                    }
                'f' -> {
                    keep(name, Regular(entry.mode))
                    write(input, path, entry.size)
                    chmod(path, fileMode(entry.mode))
                }
                'l' -> {
                    keep(name, Symlink(entry.linkName))
                    symlinks += name to entry.linkName
                }
                'h' -> {
                    keep(name, HardLink(hardTarget!!))
                    hardLinks += name
                }
                else -> keep(name, Special)
            }
        }

        /** Records a kept name; the names and link texts are held until the end, so their total is bounded. */
        private fun keep(
            name: String,
            node: Node,
        ) {
            nameChars += name.length + (if (node is Symlink) node.target.length else if (node is HardLink) node.target.length else 0)
            if (nameChars > limits.maxNameChars) throw format("has more than ${limits.maxNameChars} characters of names")
            nodes[name] = node
        }

        /** Creates the directories a name implies; every existing parent must be a real directory, never a link or a file. */
        private fun ensureParents(name: String) {
            var slash = name.indexOf('/')
            while (slash >= 0) {
                val parent = name.substring(0, slash)
                when (nodes[parent]) {
                    null -> mkdir(parent, resolve(parent), 0x1ed) // 0755
                    is Dir -> Unit
                    else -> throw format("has $name inside $parent, which isn't a directory")
                }
                slash = name.indexOf('/', slash + 1)
            }
        }

        private fun mkdir(
            name: String,
            path: Path,
            mode: Int,
        ) {
            storage("can't create a directory") {
                Files.createDirectory(path)
                chmod(path, dirMode(mode))
            }
            keep(name, Dir(mode))
        }

        private fun write(
            input: InputStream,
            path: Path,
            size: Long,
        ) {
            countWritten(size)
            val out = storage("can't create a file") { Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS) }
            out.use {
                var left = size
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw format("is cut short")
                    storage("can't write a file") { out.write(buf, 0, n) }
                    left -= n
                }
            }
        }

        private fun copyHardLinks() {
            for (name in hardLinks) {
                var target = (nodes[name] as HardLink).target
                var node = nodes[target]
                var depth = 0
                while (node is HardLink) {
                    if (++depth > limits.maxHardLinkChain) throw format("has a hard-link loop at $name")
                    target = node.target
                    node = nodes[target]
                }
                when (node) {
                    is Regular -> {
                        countWritten(storage("can't read a file") { Files.size(resolve(target)) })
                        val path = resolve(name)
                        storage("can't copy a hard link") {
                            Files.newInputStream(resolve(target), LinkOption.NOFOLLOW_LINKS).use { input ->
                                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { input.copyTo(it) }
                            }
                        }
                        chmod(path, fileMode(node.mode))
                    }
                    // A hard link to a symlink is another symlink with the same text.
                    is Symlink -> symlinks += name to node.target
                    is Special -> Unit
                    is Dir -> throw format("hard-links the directory $target")
                    else -> throw format("hard-links $target, which isn't in it")
                }
            }
        }

        /** Enough of Arch Linux ARM for setup to run: a shell, env, pacman and its config, the loader, root's home. */
        private fun checkShape() {
            fun file(name: String) = nodes[name] is Regular || nodes[name] is HardLink
            val ok =
                nodes["usr/bin"] is Dir && nodes["etc/pacman.d"] is Dir && nodes["root"] is Dir &&
                    (nodes["bin"] is Symlink || nodes["bin"] is Dir) &&
                    listOf("usr/bin/bash", "usr/bin/env", "usr/bin/pacman", "etc/pacman.conf").all(::file) &&
                    nodes["usr/bin/sh"].let { it is Symlink || file("usr/bin/sh") } &&
                    nodes["usr/lib/ld-linux-aarch64.so.1"].let { it is Symlink || file("usr/lib/ld-linux-aarch64.so.1") }
            if (!ok) throw format("doesn't look like Arch Linux ARM's rootfs")
        }

        private fun resolve(name: String): Path {
            val path = root.resolve(name)
            // memberPath already guarantees this; checked by path components, not string prefixes.
            check(path.normalize() == path && path.startsWith(root) && path != root)
            return path
        }

        private fun countWritten(size: Long) {
            written += size
            if (written > limits.maxWrittenBytes) throw format("unpacks to more than ${limits.maxWrittenBytes shr 30} GB of files")
        }
    }

    /** Sets permission bits on a file or directory itself, never through a symlink. */
    private fun chmod(
        path: Path,
        mode: Int,
    ) = storage("can't set a file's mode") {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).setPermissions(permissions(mode))
    }

    /**
     * Tar with 512-byte blocks, so nothing after the end-of-archive records is skipped unread
     * (with tar's usual 10240-byte blocks the rest of the last block would be), and strict about
     * what follows the end marker, about header checksums, and about metadata.
     *
     * Commons Compress reads a GNU long name or a PAX header into memory whole, then nests a call
     * to read the entry it belongs to. Its size comes from its own header, so that's checked here,
     * from the raw header, before the body is read; so is how many such records come in a row.
     */
    private class StrictTar(
        private val plain: Counting,
        private val limits: ExtractLimits,
    ) : TarArchiveInputStream(plain, TarConstants.DEFAULT_RCDSIZE, TarConstants.DEFAULT_RCDSIZE, "UTF-8") {
        /** Whether tar's end-of-archive record was read, rather than the stream just running out between entries. */
        var sawEnd = false

        /** Metadata records since the last entry's own header. */
        private var metadataRecords = 0

        /** Global PAX headers apply to every later entry, so Commons Compress keeps them all. */
        private var globalBytes = 0L
        private val drain = ByteArray(1 shl 16)

        /** The next entry, read within [ExtractLimits.maxHeaderBytes] of the stream. */
        fun next(): TarArchiveEntry? {
            // The rest of a skipped entry's data first, so only what precedes the next entry is fenced.
            if (currentEntry != null) {
                while (read(drain) >= 0) continue
            }
            plain.fence = plain.count + limits.maxHeaderBytes
            try {
                return nextEntry
            } finally {
                plain.fence = Long.MAX_VALUE
            }
        }

        override fun isEOFRecord(record: ByteArray?): Boolean = super.isEOFRecord(record).also { if (it && record != null) sawEnd = true }

        /** Commons Compress doesn't check header checksums itself; a damaged header must not be read as a different entry. */
        override fun readRecord(): ByteArray? {
            val record = super.readRecord() ?: return null
            if (record.any { it != 0.toByte() }) {
                // A lone zero record followed by more: tar would quietly drop the next one.
                if (sawEnd) throw IOException("data after the end-of-archive marker")
                if (!TarUtils.verifyCheckSum(record)) throw IOException("a header's checksum is wrong")
                checkMetadata(record)
            }
            return record
        }

        private fun checkMetadata(header: ByteArray) {
            when (val type = header[TYPE_OFFSET].toInt().toChar()) {
                // GNU long name and link, PAX local (and Solaris's) and global headers.
                'L', 'K', 'x', 'X', 'g' -> {
                    val size =
                        try {
                            TarUtils.parseOctalOrBinary(header, SIZE_OFFSET, SIZE_LENGTH)
                        } catch (e: IllegalArgumentException) {
                            throw format("has a metadata header with a malformed size", e)
                        }
                    if (size !in 0..limits.maxMetadataBytes) throw format("has a metadata record of $size bytes")
                    if (++metadataRecords > limits.maxMetadataRecords) throw format("has more than ${limits.maxMetadataRecords} metadata records for one entry")
                    if (type == 'g') {
                        globalBytes += size
                        if (globalBytes > limits.maxMetadataBytes) throw format("has more than ${limits.maxMetadataBytes} bytes of global metadata")
                    }
                }
                // An old GNU sparse file: its extension headers would be read before it's returned and refused.
                'S' -> throw format("has a sparse file, which isn't supported")
                else -> metadataRecords = 0
            }
        }

        private companion object {
            // ustar header fields.
            const val SIZE_OFFSET = 124
            const val SIZE_LENGTH = 12
            const val TYPE_OFFSET = 156
        }
    }

    private class Counting(
        input: InputStream,
        private val max: Long,
        private val tooMuch: () -> Throwable,
    ) : FilterInputStream(input) {
        var count = 0L
            private set

        /** Reading past this position fails; see [StrictTar.next]. */
        var fence = Long.MAX_VALUE

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) add(it) }

        private fun add(n: Long) {
            count += n
            if (count > max) throw tooMuch()
            if (count > fence) throw format("has more metadata before an entry than the launcher reads")
        }
    }
}

/**
 * The relative path a tar member name stands for, or null if it isn't safe as one. A leading
 * "./" and, for directories only, trailing slashes are dropped; "" is the archive's root.
 */
internal fun memberPath(
    raw: String,
    directory: Boolean,
    limits: ExtractLimits = ExtractLimits(),
): String? {
    if (raw.isEmpty() || raw.any { it < ' ' || it == '\u007f' || it == '�' }) return null
    var name =
        when {
            raw == "." -> ""
            raw.startsWith("./") -> raw.substring(2)
            else -> raw
        }
    if (name.startsWith("/")) return null
    if (name.endsWith("/")) {
        if (!directory) return null
        name = name.trimEnd('/')
    }
    if (name.isEmpty()) return name
    if (name.toByteArray().size > limits.maxPathBytes) return null
    for (part in name.split('/')) {
        if (part.isEmpty() || part == "." || part == ".." || part.toByteArray().size > limits.maxNameBytes) return null
    }
    return name
}

/** A symlink's text is the guest's business, absolute or with ".."; it only has to be storable. */
internal fun linkTextOk(
    target: String,
    limits: ExtractLimits = ExtractLimits(),
) = target.isNotEmpty() && '\u0000' !in target && '�' !in target && target.toByteArray().size <= limits.maxLinkBytes

/** The parts of upstream's image that are useless under proot (see [RootfsExtractor]). */
internal fun skipped(name: String) =
    name == "boot" ||
        name.startsWith("boot/") ||
        name.startsWith("usr/lib/firmware/") ||
        name.startsWith("usr/lib/modules/") ||
        name.startsWith("dev/")

/** Permission bits only, and always owner read/write: the launcher updates these files and deletes the tree. */
internal fun fileMode(mode: Int) = mode and 0x1ff or 0x180

/** Directories also stay enterable by the owner, or nothing inside could be created or deleted. */
internal fun dirMode(mode: Int) = mode and 0x1ff or 0x1c0

private fun permissions(mode: Int): Set<PosixFilePermission> {
    val all = PosixFilePermission.entries // OWNER_READ .. OTHERS_EXECUTE, highest bit first
    return all.filterIndexedTo(HashSet()) { i, _ -> mode and (0x100 shr i) != 0 }
}

private fun format(
    message: String,
    cause: Throwable? = null,
) = RootfsException(RootfsException.Kind.Format, "the tarball $message", cause)

/** Runs [block], reporting a failure to write or read the phone's storage as such rather than as a bad archive. */
private inline fun <T> storage(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: RootfsException) {
        throw e
    } catch (e: IOException) {
        throw RootfsException(RootfsException.Kind.Storage, "$what: ${e.message}", e)
    }
