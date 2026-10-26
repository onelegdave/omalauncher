package dev.omalauncher.linux

import dev.omalauncher.upgrade.writeAtomically
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/** Upstream's name for the generic aarch64 rootfs; its detached signature is this plus ".sig". */
const val ROOTFS_TARBALL = "ArchLinuxARM-aarch64-latest.tar.gz"

/**
 * Official Arch Linux ARM mirrors that serve the rootfs over HTTPS with a certificate for their
 * own name. os.archlinuxarm.org, the geo redirector the launcher used before, answers HTTPS with
 * a certificate for another name. The signature is what authenticates the tarball; HTTPS keeps
 * what's downloaded private and makes tampering on the way a failed check instead of a long
 * wasted download.
 */
val ROOTFS_MIRRORS =
    listOf(
        "https://fl.us.mirror.archlinuxarm.org/os/",
        "https://de3.mirror.archlinuxarm.org/os/",
        "https://ca.us.mirror.archlinuxarm.org/os/",
    )

/**
 * The biggest tarball accepted, downloaded or pushed. It was 829 MB on 2026-08-05; this
 * leaves room for it to more than double before the app needs a new limit.
 */
const val MAX_ARCHIVE_BYTES = 2L shl 30

/** Bounds on what a mirror may send and how long it may take to answer. */
class DownloadLimits(
    val maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
    val maxSignatureBytes: Int = MAX_SIGNATURE_BYTES,
    val maxRedirects: Int = 5,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 30_000,
)

/**
 * Downloads the tarball and its signature into [cacheDir]. Nothing here trusts what it fetched:
 * a complete file is only a candidate for [RootfsVerifier].
 *
 * An interrupted download resumes with a range request, but only while the server still has the
 * same file: If-Range with the ETag or Last-Modified that URL gave. "latest" moves, and two
 * versions mustn't be spliced. Another mirror may continue it only with the same Last-Modified
 * and length (they copy the file with its time; fl.us, de3 and ca.us agreed on 2026-10-11).
 * Any reply that doesn't continue exactly where the partial file ends starts the download over.
 */
class RootfsDownloader(
    private val cacheDir: File,
    private val limits: DownloadLimits = DownloadLimits(),
    /** Which URLs, including redirect targets, may be fetched: HTTPS only, except in tests. */
    private val allowed: (URL) -> Boolean = { it.protocol == "https" },
    private val ensureActive: () -> Unit = {},
    private val progress: (done: Long, total: Long) -> Unit = { _, _ -> },
) {
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    /** Unblocks a header/body read where the platform permits it; timeouts bound the remaining wait. */
    fun cancel() {
        cancelled = true
        connection?.let { conn -> thread(name = "rootfs-cancel", isDaemon = true) { runCatching { conn.disconnect() } } }
    }

    private fun checkActive() {
        if (cancelled) throw CancellationException("cancelled")
        ensureActive()
    }

    private fun disconnect(conn: HttpURLConnection) {
        if (connection === conn) connection = null
        conn.disconnect()
    }

    /** A finished download, waiting to be verified. */
    val archive = File(cacheDir, ROOTFS_TARBALL)
    private val partial = File(cacheDir, "$ROOTFS_TARBALL.part")
    private val partialInfo = File(cacheDir, "$ROOTFS_TARBALL.part.json")

    /** Launchers before signature checks kept the partial file's validator here, without its URL. */
    private val legacyValidator = File(cacheDir, "$ROOTFS_TARBALL.version")

    /** The URL an unfinished download came from, so a retry can continue it there. */
    val partialUrl: String? get() = readInfo()?.url
    val partialBytes: Long get() = if (partial.isFile && readInfo() != null) partial.length() else 0L

    /** The detached signature at [url], whole; it's small, so it's never resumed. */
    fun signature(url: String): ByteArray {
        val conn = open(url) {}
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw network("the signature download failed: HTTP ${conn.responseCode}")
            checkIdentity(conn)
            if (conn.contentLengthLong > limits.maxSignatureBytes) throw network("the signature download is too big")
            return conn.inputStream.use { readAtMost(it, limits.maxSignatureBytes, ::checkActive) } ?: throw network("the signature download is too big")
        } catch (e: RootfsException) {
            throw e
        } catch (e: IOException) {
            checkActive()
            throw network("the signature download failed: ${e.message}", e)
        } finally {
            disconnect(conn)
        }
    }

    /**
     * Downloads [url] into [archive], continuing an unfinished download of the same file. A partial
     * file stays until a server's reply to replace it checks out (a whole file, not re-encoded,
     * not too big), so failing to reach a mirror, or a mirror that answers unusably, loses nothing.
     * Throws [RootfsException.Kind.Interrupted] if a transfer that can be continued broke off
     * after progress.
     */
    fun download(url: String): File {
        dropLegacyPartial()
        // At most one restart within a call: after that a misbehaving server fails the attempt.
        var restarted = false
        // After a reply that couldn't continue the partial file: ask for the whole file, which then replaces it.
        var whole = false
        while (true) {
            checkActive()
            val info = readInfo()?.takeIf { !whole && partial.isFile && it.ifRange(url) != null }
            val have = if (info != null) partial.length() else 0L
            if (info != null && info.total >= 0) {
                if (have == info.total) return promote()
                if (have > info.total) {
                    discardPartial()
                    continue
                }
            }
            val ranged = have > 0
            val conn =
                open(url) { c ->
                    if (ranged) {
                        c.setRequestProperty("Range", "bytes=$have-")
                        c.setRequestProperty("If-Range", info!!.ifRange(url))
                    }
                }
            try {
                val code = conn.responseCode
                checkIdentity(conn)
                val start: Long
                val total: Long
                // Whether a cut-off transfer is worth continuing at this server, on the next try.
                val resumable: Boolean
                when {
                    code == HttpURLConnection.HTTP_PARTIAL && ranged -> {
                        val known = info!!
                        val range = contentRange(conn)
                        if (range == null || !continues(range, have, known, url, conn)) {
                            // Appending would splice two different files, or leave a hole.
                            if (restarted) throw network("the server's reply didn't continue the download")
                            restarted = true
                            whole = true
                            continue
                        }
                        start = have
                        total = range.third
                        // From now on it continues here, with what this server calls the file.
                        if (known.url != url || (known.total < 0 && total >= 0)) {
                            writeInfo(PartialInfo(url, validator(conn) ?: known.validator, total, lastModified(conn) ?: known.modified))
                        }
                        resumable = readInfo()?.ifRange(url) != null
                    }
                    code == 416 || code == HttpURLConnection.HTTP_PARTIAL -> {
                        // 416: the file shrank, so it's a different one now. A 206 to a request without a range is nonsense.
                        if (restarted) throw network("the server refused to continue the download (HTTP $code)")
                        restarted = true
                        whole = true
                        continue
                    }
                    code == HttpURLConnection.HTTP_OK -> {
                        // A whole file: the server ignored the range, or the file changed (If-Range). Start over,
                        // once the reply could be the tarball at all: until then the partial file stays.
                        total = conn.contentLengthLong
                        if (total > limits.maxArchiveBytes) throw tooBig()
                        discardPartial()
                        start = 0
                        writeInfo(PartialInfo(url, validator(conn), total, lastModified(conn)))
                        // A server that answered a range request with the whole file (or with a range that
                        // didn't fit) won't continue this one either: if it breaks off, the next mirror is
                        // tried, not this one again.
                        resumable = !ranged && !restarted && validator(conn) != null && conn.getHeaderField("Accept-Ranges").equals("bytes", ignoreCase = true)
                    }
                    else -> throw network("the download failed: HTTP $code")
                }
                receive(conn, start, total, resumable)
            } catch (e: RootfsException) {
                throw e
            } catch (e: IOException) {
                checkActive()
                // Before any of the body: receive() reports its own failures.
                throw network("the download failed (${e.message})", e)
            } finally {
                disconnect(conn)
            }
            checkActive()
            return promote()
        }
    }

    /** Deletes every download file the app made; never the user's own copy outside [cacheDir]. */
    fun clear() {
        discardPartial()
        archive.delete()
    }

    /** Writes the body after [start] bytes already in [partial]; whatever arrives stays for a retry to continue. */
    private fun receive(
        conn: HttpURLConnection,
        start: Long,
        total: Long,
        resumable: Boolean,
    ) {
        var done = start

        // Only a transfer that got somewhere, and that this server can continue, is worth retrying there.
        fun cut(
            why: String,
            cause: Throwable? = null,
        ) = if (resumable && done > start) {
            RootfsException(RootfsException.Kind.Interrupted, "$why; retry to continue it", cause)
        } else {
            network(why, cause)
        }
        try {
            conn.inputStream.use { input ->
                FileOutputStream(partial, start > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    progress(done, total)
                    while (true) {
                        checkActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (done + n > limits.maxArchiveBytes) {
                            discardPartial()
                            throw tooBig()
                        }
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 4 shl 20) {
                            lastReport = done
                            progress(done, total)
                        }
                    }
                    out.fd.sync()
                    if (total >= 0 && done != total) throw cut("the download was cut short")
                }
            }
        } catch (e: RootfsException) {
            throw e
        } catch (e: IOException) {
            checkActive()
            throw cut("the download was interrupted (${e.message})", e)
        }
    }

    private fun promote(): File {
        try {
            Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw RootfsException(RootfsException.Kind.Storage, "can't keep the download", e)
        }
        partialInfo.delete()
        return archive
    }

    private fun discardPartial() {
        partial.delete()
        partialInfo.delete()
    }

    private fun dropLegacyPartial() {
        if (legacyValidator.exists()) {
            // Its URL is unknown (the old redirector sent it to any mirror), so it can't be continued safely.
            legacyValidator.delete()
            if (!partialInfo.exists()) partial.delete()
        }
    }

    /** Opens [url] with [setup] applied on every hop, following at most [DownloadLimits.maxRedirects] allowed redirects. */
    private fun open(
        url: String,
        setup: (HttpURLConnection) -> Unit,
    ): HttpURLConnection {
        var current = URL(url)
        for (hop in 0..limits.maxRedirects) {
            checkActive()
            if (!allowed(current)) throw network("refusing to download from ${current.protocol}://${current.host}: not a secure address")
            val conn = current.openConnection() as HttpURLConnection
            connection = conn
            conn.instanceFollowRedirects = false
            conn.connectTimeout = limits.connectTimeoutMs
            conn.readTimeout = limits.readTimeoutMs
            conn.useCaches = false
            // Bytes exactly as stored: they are what's signed, and transparent decompression would break ranges.
            conn.setRequestProperty("Accept-Encoding", "identity")
            setup(conn)
            val code =
                try {
                    checkActive()
                    conn.responseCode
                } catch (e: Throwable) {
                    disconnect(conn)
                    checkActive()
                    if (e is IOException) throw network("can't reach ${current.host} (${e.message})", e)
                    throw e
                }
            if (code !in 300..399 || code == HttpURLConnection.HTTP_NOT_MODIFIED) return conn
            val location = conn.getHeaderField("Location")
            disconnect(conn)
            current = URL(current, location ?: throw network("a redirect without a target (HTTP $code)"))
        }
        throw network("too many redirects")
    }

    private fun checkIdentity(conn: HttpURLConnection) {
        val encoding = conn.getHeaderField("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw network("the server re-encoded the download ($encoding)")
    }

    /** A strong ETag, else Last-Modified: what If-Range may name. Null if the server gives neither. */
    private fun validator(conn: HttpURLConnection): String? =
        conn.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: lastModified(conn)

    private fun lastModified(conn: HttpURLConnection): String? = conn.getHeaderField("Last-Modified")

    /**
     * Whether a 206 reply continues the partial file exactly: the same file, from where the partial
     * one ends, to its end. From another mirror only one that names the same Last-Modified counts.
     */
    private fun continues(
        range: Triple<Long, Long, Long>,
        have: Long,
        info: PartialInfo,
        url: String,
        conn: HttpURLConnection,
    ) = range.first == have &&
        (if (url == info.url) (validator(conn) ?: info.validator) == info.validator else lastModified(conn) == info.modified) &&
        (info.total < 0 || range.third == info.total) &&
        (range.third < 0 || range.second == range.third - 1) &&
        (conn.contentLengthLong < 0 || conn.contentLengthLong == range.second - range.first + 1)

    /** "bytes first-last/total" as (first, last, total), total -1 for "*"; null if malformed. */
    private fun contentRange(conn: HttpURLConnection): Triple<Long, Long, Long>? {
        val m = CONTENT_RANGE.matchEntire(conn.getHeaderField("Content-Range")?.trim() ?: return null) ?: return null
        val first = m.groupValues[1].toLongOrNull() ?: return null
        val last = m.groupValues[2].toLongOrNull() ?: return null
        val total = if (m.groupValues[3] == "*") -1L else m.groupValues[3].toLongOrNull() ?: return null
        if (last < first || (total >= 0 && last >= total)) return null
        return Triple(first, last, total)
    }

    private data class PartialInfo(
        val url: String,
        val validator: String?,
        val total: Long,
        /** The file's Last-Modified, which the mirrors agree on. */
        val modified: String?,
    ) {
        /**
         * What If-Range names to continue this download from [from]: the validator its own server
         * gave, or for another mirror Last-Modified, if the length is known. Null: it can't be.
         */
        fun ifRange(from: String) = if (from == url) validator else modified?.takeIf { total >= 0 }
    }

    private fun readInfo(): PartialInfo? =
        runCatching {
            val o = JSONObject(partialInfo.readText())
            fun text(key: String) = o.optString(key).takeIf { it.isNotEmpty() }
            PartialInfo(o.getString("url"), text("validator"), o.getLong("total"), text("modified"))
        }.getOrNull()

    private fun writeInfo(info: PartialInfo) =
        try {
            val o = JSONObject().put("url", info.url).put("validator", info.validator ?: "").put("total", info.total).put("modified", info.modified ?: "")
            writeAtomically(partialInfo, o.toString().toByteArray())
        } catch (e: Exception) {
            throw RootfsException(RootfsException.Kind.Storage, "can't save the download's progress", e)
        }

    /** A mirror's problem, not the tarball's: another mirror may serve the real one. */
    private fun tooBig() = network("the download is bigger than an Arch Linux ARM tarball can be")

    private companion object {
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}

/** Reads [input] whole, or returns null once it holds more than [max] bytes. */
internal fun readAtMost(
    input: InputStream,
    max: Int,
    ensureActive: () -> Unit = {},
): ByteArray? {
    // Not InputStream.readNBytes: Android has it only from API 33.
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (true) {
        ensureActive()
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
}

/** Tarballs one try of [RootfsSource.obtain] may download: a fresh pair after a mismatch, and no more. */
private const val MAX_DOWNLOADS = 2

private fun network(
    message: String,
    cause: Throwable? = null,
) = RootfsException(RootfsException.Kind.Network, message, cause)

/**
 * Where a verified tarball comes from. Every path ends at [RootfsVerifier]: a tarball the user
 * pushed (with its .sig) to the app's external files dir, a finished download from an earlier
 * try, or a fresh download. Nothing is trusted for existing, for its name, or for an old check.
 */
class RootfsSource(
    private val cacheDir: File,
    /** Where adb can push files: Android/data/dev.omalauncher/files. */
    private val externalDir: File?,
    private val verifier: RootfsVerifier,
    private val downloader: RootfsDownloader,
    private val mirrors: List<String> = ROOTFS_MIRRORS,
    private val ensureActive: () -> Unit = {},
    /** The biggest tarball a user may push. */
    private val maxPushedBytes: Long = MAX_ARCHIVE_BYTES,
    private val beforeDownload: () -> Unit = {},
    private val beforeCopy: (bytes: Long) -> Unit = {},
    private val report: (step: String, progress: Float?) -> Unit = { _, _ -> },
) {
    /** The private copy of a pushed tarball: checked and unpacked from here, so changing the pushed file meanwhile can't matter. */
    val snapshot = File(cacheDir, "$ROOTFS_TARBALL.pushed")

    /** Mirrors whose own signature didn't fit a whole tarball they sent: asked last from then on, until an install succeeds. */
    private val suspects = File(cacheDir, "$ROOTFS_TARBALL.mismatched")

    /** Tarballs fully downloaded in this try, up to [MAX_DOWNLOADS]: each is up to 2 GB. */
    private var downloads = 0

    /**
     * The verified tarball, from the first mirror that can deliver one. A mirror that can't be
     * reached, or sends something unusable, gives way to the next. A transfer that broke off
     * after progress, at a mirror that can continue it, ends the try instead: the step's next try
     * continues it there, first, rather than another mirror starting 830 MB over. Another mirror
     * is tried only when that one is down or won't continue it, and it continues the partial file
     * if it can.
     *
     * A mirror whose signature doesn't fit the whole tarball it just sent gives way to the next for
     * a fresh pair, and comes last in later tries; at most [MAX_DOWNLOADS] tarballs per try.
     */
    fun obtain(): VerifiedRootfs {
        ensureActive()
        val pushed = externalDir?.let { File(it, ROOTFS_TARBALL) }
        if (pushed != null && pushed.exists()) return fromPushed(pushed)
        var failure: RootfsException? = null
        downloads = 0
        val partialUrl = downloader.partialUrl
        val suspect = readSuspects()
        val order = mirrors.sortedBy { if (partialUrl?.startsWith(it) == true) 0 else if (it in suspect) 2 else 1 }
        for (mirror in order) {
            if (downloads >= MAX_DOWNLOADS) break
            try {
                return fromMirror(mirror)
            } catch (e: RootfsException) {
                failure =
                    when (e.kind) {
                        // A mismatch ends the step: its retries would download again. Then a refused signer, then the rest.
                        RootfsException.Kind.Mismatch -> e
                        // Maybe just this mirror; trust never widens, so another one's signature must be by the same key.
                        RootfsException.Kind.Signer -> failure?.takeIf { it.kind == RootfsException.Kind.Mismatch } ?: e
                        RootfsException.Kind.Network -> failure ?: e
                        else -> throw e
                    }
            }
        }
        throw failure ?: network("no mirror to download from")
    }

    /** Deletes the app's own copies once the rootfs is unpacked (or must be fetched anew); the user's pushed files stay. */
    fun clear() {
        downloader.clear()
        dropSnapshot()
        suspects.delete()
    }

    /** Deletes the private copy of a pushed tarball, which every try makes afresh. A download stays. */
    fun dropSnapshot() {
        snapshot.delete()
    }

    private fun fromPushed(pushed: File): VerifiedRootfs {
        val sigFile = File(pushed.parentFile, "$ROOTFS_TARBALL.sig")
        if (!sigFile.isFile) {
            throw RootfsException(
                RootfsException.Kind.MissingSignature,
                "$ROOTFS_TARBALL was pushed without its signature: push $ROOTFS_TARBALL.sig next to it " +
                    "(Android/data/dev.omalauncher/files), or delete the tarball there to download it instead",
            )
        }
        val signature =
            try {
                sigFile.inputStream().use { readAtMost(it, MAX_SIGNATURE_BYTES) }
            } catch (e: IOException) {
                throw RootfsException(RootfsException.Kind.Storage, "can't read the pushed signature", e)
            } ?: throw RootfsException(RootfsException.Kind.Signature, "the pushed $ROOTFS_TARBALL.sig is too big to be a signature")
        // Before copying 830 MB: a signature this app would refuse anyway is reported right away.
        try {
            verifier.check(signature)
        } catch (e: RootfsException) {
            throw pushedProblem(e)
        }
        report("Copying the pushed tarball", null)
        beforeCopy(pushed.length())
        try {
            snapshot.delete()
            pushed.inputStream().use { input ->
                FileOutputStream(snapshot).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > maxPushedBytes) {
                            throw RootfsException(
                                RootfsException.Kind.Format,
                                "the pushed $ROOTFS_TARBALL is bigger than an Arch Linux ARM tarball can be: push the one from " +
                                    "archlinuxarm.org with its .sig, or delete it there to download it instead",
                            )
                        }
                        out.write(buf, 0, n)
                    }
                    out.fd.sync()
                }
            }
        } catch (e: Throwable) {
            snapshot.delete()
            if (e is RootfsException) throw e
            if (e !is IOException) throw e
            throw RootfsException(RootfsException.Kind.Storage, "can't copy the pushed tarball", e)
        }
        return try {
            verify(snapshot, signature)
        } catch (e: RootfsException) {
            snapshot.delete()
            throw pushedProblem(e)
        } catch (e: Throwable) {
            snapshot.delete()
            throw e
        }
    }

    private fun pushedProblem(e: RootfsException) =
        when (e.kind) {
            RootfsException.Kind.Mismatch ->
                RootfsException(e.kind, "the pushed $ROOTFS_TARBALL doesn't match the pushed $ROOTFS_TARBALL.sig: push a matching pair", e)
            RootfsException.Kind.Signature ->
                RootfsException(e.kind, "the pushed $ROOTFS_TARBALL.sig isn't a readable signature (${e.message}): push the .sig published with the tarball", e)
            RootfsException.Kind.Signer -> RootfsException(e.kind, "the pushed ${refusedSignature(e)}", e)
            else -> e
        }

    /**
     * Signature, then tarball (a finished one from an earlier try, else downloaded or continued).
     * "latest" can move between the two requests, so a pair that doesn't match gets a fresh
     * signature. Bytes that didn't all just come from here (kept, or continued from a partial
     * file) then get one fresh copy from here. A fresh whole copy that still doesn't match makes
     * this mirror a suspect: [obtain] goes on to the next one.
     */
    private fun fromMirror(base: String): VerifiedRootfs {
        val url = base + ROOTFS_TARBALL
        val sigUrl = "$url.sig"
        report("Downloading Arch Linux ARM", null)
        val first = signature(sigUrl)
        // Whether the bytes checked are a whole copy this mirror just sent.
        var whole = !downloader.archive.isFile && downloader.partialUrl == null
        val archive = if (downloader.archive.isFile) downloader.archive else download(url)
        attempt(archive, first)?.let { return it }

        val second = signature(sigUrl)
        if (!second.contentEquals(first)) attempt(archive, second)?.let { return it }

        // Neither signature fits these bytes: they're damaged, or another release. Only a fresh copy helps.
        downloader.clear()
        if (!whole && downloads < MAX_DOWNLOADS) {
            attempt(download(url), signature(sigUrl))?.let { return it }
            downloader.clear()
            whole = true
        }
        if (whole) suspect(base)
        throw RootfsException(
            RootfsException.Kind.Mismatch,
            "the download didn't match Arch Linux ARM's signature: it was damaged on the way, or a new release " +
                "was being published. Try again later",
        )
    }

    private fun download(url: String): File {
        ensureActive()
        beforeDownload()
        return downloader.download(url).also { downloads++ }
    }

    /** The signature at [sigUrl], once it's one the app could accept: a refused one costs no tarball download. */
    private fun signature(sigUrl: String): ByteArray {
        val signature = downloader.signature(sigUrl)
        try {
            verifier.check(signature)
        } catch (e: RootfsException) {
            throw downloadedProblem(e)
        }
        return signature
    }

    /** The verified tarball, or null if the signature is well-formed but doesn't fit these bytes. */
    private fun attempt(
        archive: File,
        signature: ByteArray,
    ): VerifiedRootfs? =
        try {
            verify(archive, signature)
        } catch (e: RootfsException) {
            if (e.kind == RootfsException.Kind.Mismatch) null else throw downloadedProblem(e)
        }

    private fun downloadedProblem(e: RootfsException) =
        when (e.kind) {
            // A captive portal's page or a cut-off reply instead of the signature: worth another try, keeping the tarball.
            RootfsException.Kind.Signature -> network("the signature download is damaged (${e.message})", e)
            // Readable, and refused: not damage. Another mirror may have the right one; nothing else does.
            RootfsException.Kind.Signer -> RootfsException(e.kind, "the downloaded ${refusedSignature(e)}", e)
            else -> e
        }

    private fun readSuspects(): Set<String> = runCatching { suspects.readLines().toSet() }.getOrDefault(emptySet())

    /** Only an ordering hint: failing to save it changes nothing else. */
    private fun suspect(base: String) {
        runCatching { writeAtomically(suspects, (readSuspects().filter { it in mirrors } + base).toSet().joinToString("") { "$it\n" }.toByteArray()) }
    }

    private fun refusedSignature(e: RootfsException) =
        "$ROOTFS_TARBALL.sig isn't one this app accepts (${e.message}). If Arch Linux ARM has changed its signing key, " +
            "the app needs an update before it can install Linux"

    private fun verify(
        archive: File,
        signature: ByteArray,
    ): VerifiedRootfs {
        report("Checking the download's signature", 0f)
        return verifier.verify(archive, signature, ensureActive) { done, total ->
            report("Checking the download's signature", if (total > 0) done.toFloat() / total else null)
        }
    }
}
