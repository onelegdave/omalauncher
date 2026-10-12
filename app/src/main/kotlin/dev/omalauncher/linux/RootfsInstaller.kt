package dev.omalauncher.linux

import dev.omalauncher.upgrade.writeAtomically
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/** The marker file an activated rootfs carries at its top; the archive may not contain it. */
const val ROOTFS_TOKEN = ".omalauncher-rootfs"

/**
 * Puts a verified rootfs in place: unpacks it into a fresh staging directory next to [rootfs],
 * then makes it the rootfs with one rename. Nothing a guest could run, and nothing recorded
 * about the system, changes until unpacking has completely succeeded; a failure or a killed
 * process leaves at most a staging directory that the next try deletes.
 *
 * The rename and the journal's "rootfs" mark are two steps, so the app can die between them.
 * Before the rename a random token goes into the staged tree and into [activation], outside it;
 * a rootfs whose token matches is one this installer completed, and a retry keeps it instead of
 * downloading again. The token can't come from the archive (the name is reserved) nor from an
 * older launcher's half-unpacked tree, since it's made fresh each time.
 *
 * This holds when the process is killed. A power cut can lose file contents written shortly
 * before it, as with any unsynced write; the tree isn't synced file by file.
 */
class RootfsInstaller(
    filesDir: File,
    /** Whether a finished setup owns the rootfs (the journal says so, or an older launcher's rootfs-ready). */
    private val setUp: () -> Boolean,
    /** Forgets what was recorded about an earlier rootfs (journal, provisioned files); runs right before the switch. */
    private val forgetOldRootfs: () -> Unit,
    /** Deletes a tree without following symlinks: LauncherData.deleteTree in the app. */
    private val deleteTree: (File) -> Unit,
    private val report: (stage: Stage, step: String, progress: Float?) -> Unit = { _, _, _ -> },
) {
    val rootfs = File(filesDir, "arch")
    val staging = File(filesDir, "arch.staging")
    private val activation = File(filesDir, "rootfs-activation.json")

    /**
     * Held while installing or removing: a removal still deleting by path (say the leftovers
     * the app checks for when it starts) must never run into a tree an install is putting there.
     */
    private val lock = Any()

    /**
     * Gets, checks, unpacks and activates a rootfs; or, if the app died right after activating
     * one, recognizes it. Refuses to touch a set-up system. Waits for a removal to finish first.
     */
    fun install(
        source: RootfsSource,
        extractor: RootfsExtractor,
    ): Unit =
        synchronized(lock) {
            installLocked(source, extractor)
        }

    private fun installLocked(
        source: RootfsSource,
        extractor: RootfsExtractor,
    ) {
        check(!setUp()) { "refusing to replace a set-up system" }
        if (activated()) {
            source.clear()
            return
        }
        val verified = source.obtain()
        try {
            report(Stage.Extract, "Starting", 0f)
            freshStaging()
            extractor.extract(verified, staging)
            activate(verified.sha256)
        } catch (e: Throwable) {
            runCatching { deleteTree(staging) }
            // Bytes that changed under the unpacker are fetched again. A tarball that checked out but
            // can't be unpacked stays: the same bytes fail the same way, so downloading them again
            // can't help. Each try re-checks it against a fresh signature, and a new release shows
            // up as a mismatch there and gets downloaded.
            if (e is RootfsException && e.kind == RootfsException.Kind.Changed) source.clear() else source.dropSnapshot()
            throw e
        } finally {
            verified.close()
        }
        // ~830 MB we no longer need. The user's pushed tarball stays.
        source.clear()
    }

    /** What [remove] got done. */
    sealed interface Removal {
        /** Nothing of the rootfs is left. */
        data object Done : Removal

        /** Nothing was deleted, and the system is still what it was: [forget] didn't run, or failed. */
        class Kept(
            val cause: Exception,
        ) : Removal

        /**
         * The system is forgotten, but [cause] stopped some of its files being deleted. Nothing of
         * them can pass for an activated rootfs: [install] replaces them, or another [remove] goes on.
         */
        class Leftovers(
            val cause: Exception,
        ) : Removal
    }

    /**
     * Deletes the rootfs and staging, after the activation record and then [forget] (the caller's
     * records that a system is set up): once those are gone, nothing left of a tree can pass for
     * an installed or activated one, however far deleting gets. If either fails, nothing is
     * deleted and the system stays as it was. Then both trees are deleted as far as they can be.
     */
    fun remove(forget: () -> Unit = {}): Removal =
        synchronized(lock) {
            removeLocked(forget)
        }

    private fun removeLocked(forget: () -> Unit): Removal {
        try {
            Files.deleteIfExists(activation.toPath())
        } catch (e: IOException) {
            return Removal.Kept(RootfsException(RootfsException.Kind.Storage, "can't delete the rootfs's activation record", e))
        }
        try {
            forget()
        } catch (e: Exception) {
            return Removal.Kept(e)
        }
        var failure: Exception? = null
        for (tree in listOf(staging, rootfs)) {
            try {
                deleteTree(tree)
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        return failure?.let { Removal.Leftovers(it) } ?: Removal.Done
    }

    /** Whether [rootfs] is one this installer activated (it carries the token [activation] names). */
    fun activated(): Boolean {
        val token = rootfs.toPath().resolve(ROOTFS_TOKEN)
        if (!Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(token, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            val record = JSONObject(activation.readText())
            record.getInt("version") == ACTIVATION_VERSION &&
                record.getString("token").length == 32 &&
                record.getString("token") == String(Files.readAllBytes(token))
        }.getOrDefault(false)
    }

    /** An empty, real staging directory: whatever an interrupted try left there goes first. */
    private fun freshStaging() {
        deleteTree(staging)
        try {
            Files.createDirectory(staging.toPath())
        } catch (e: IOException) {
            throw RootfsException(RootfsException.Kind.Storage, "can't create the staging directory", e)
        }
    }

    private fun activate(sha256: String) {
        val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        try {
            Files.newOutputStream(staging.toPath().resolve(ROOTFS_TOKEN), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                it.write(token.toByteArray())
            }
        } catch (e: IOException) {
            throw RootfsException(RootfsException.Kind.Storage, "can't mark the unpacked system", e)
        }
        // Rechecked right before the switch: only a rootfs no setup finished on may be replaced.
        check(!setUp()) { "refusing to replace a set-up system" }
        // Nothing recorded about an earlier rootfs holds for this one. Forgotten first, so dying
        // between here and the rename leaves no stale records, only a retry.
        forgetOldRootfs()
        writeAtomically(activation, JSONObject().put("version", ACTIVATION_VERSION).put("token", token).put("sha256", sha256).toString().toByteArray())
        // What's here is an incomplete earlier try (a set-up one was refused above). A link is removed, not followed.
        if (Files.exists(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteTree(rootfs)
        try {
            Files.move(staging.toPath(), rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw RootfsException(RootfsException.Kind.Storage, "can't move the unpacked system into place", e)
        }
    }

    private companion object {
        const val ACTIVATION_VERSION = 1
    }
}
