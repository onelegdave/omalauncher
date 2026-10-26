package dev.omalauncher.linux

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.util.encoders.Hex
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * The Arch Linux ARM Build System key, which signs the rootfs tarballs (archlinuxarm.org/about/downloads
 * and /about/package-signing). Its public half, without the encryption subkey or third-party
 * certifications, is assets/[ROOTFS_SIGNER_ASSET]. Changing either is a reviewed source change:
 * see docs/ARCHITECTURE.md › the Linux system.
 */
const val ROOTFS_SIGNER_FINGERPRINT = "68B3537F39A313B3E574D06777193F152BDBE6A6"
const val ROOTFS_SIGNER_ASSET = "archlinuxarm-build-system.gpg"

/** A detached signature is ~600 bytes (RSA 4096); anything much bigger isn't one. */
const val MAX_SIGNATURE_BYTES = 16 * 1024

/** Why getting or unpacking the rootfs failed; [Kind.retryable] says whether trying again right away can help. */
class RootfsException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Kind(
        val retryable: Boolean,
    ) {
        /** A mirror couldn't be reached or answered unusably, leaving nothing worth coming back for; another mirror may work. */
        Network(true),

        /** The tarball's transfer broke off after making progress; what arrived is kept, and the next try continues it there. */
        Interrupted(true),

        /** Writing to the phone's storage failed, e.g. it's full. */
        Storage(true),

        /** The tarball was pushed without its .sig. */
        MissingSignature(false),

        /** The signature isn't readable as one detached OpenPGP signature: damaged, cut off, or something else entirely. */
        Signature(false),

        /**
         * A well-formed signature the app won't accept: by another key, or of a kind it doesn't
         * support. If Arch Linux ARM changed its key or how it signs, only an app update helps.
         */
        Signer(false),

        /** The tarball's bytes don't match a well-formed signature by the pinned key. */
        Mismatch(false),

        /** The verified tarball isn't an archive we can unpack safely, or is too big. */
        Format(false),

        /** The verified tarball's bytes changed while they were unpacked; the app's copy is dropped and fetched again. */
        Changed(false),
    }
}

/**
 * Checks detached OpenPGP signatures over the rootfs tarball against one pinned key, in the
 * app itself: before anything reads the tarball as an archive, and without the guest's gpg,
 * which would mean trusting the download to vouch for itself.
 *
 * Only what Arch Linux ARM actually publishes is accepted: one v4 binary-document signature made
 * directly by the pinned primary key, RSA, SHA-256 or stronger. The issuer key ID merely has to
 * agree; the cryptographic check against the pinned key is what counts. Bouncy Castle's
 * lightweight API does the cryptography, so Android's security providers are left alone.
 */
class RootfsVerifier(
    keyRing: ByteArray,
    fingerprint: String = ROOTFS_SIGNER_FINGERPRINT,
) {
    private val key: PGPPublicKey

    init {
        val want = Hex.decode(fingerprint)
        require(want.size == 20) { "a v4 fingerprint has 20 bytes" }
        val rings = BcPGPPublicKeyRingCollection(keyRing)
        require(rings.size() == 1) { "the signer asset holds one key" }
        val primary = rings.keyRings.next().publicKey
        require(primary.fingerprint.contentEquals(want)) { "the signer asset isn't the pinned key" }
        require(primary.version == 4 && primary.algorithm in RSA) { "unsupported signer key" }
        // The pinned key has neither; one that grows them needs its policy reviewed, not silently followed.
        require(!primary.hasRevocation() && primary.validSeconds == 0L) { "the signer key is revoked or expires" }
        key = primary
    }

    /**
     * Streams [archive] through the signature check. On success returns a handle on the open
     * file, so what's unpacked later is what was checked even if the path is replaced meanwhile.
     */
    fun verify(
        archive: File,
        signature: ByteArray,
        ensureActive: () -> Unit = {},
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): VerifiedRootfs {
        ensureActive()
        val sig = parse(signature)
        val channel =
            try {
                FileChannel.open(archive.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                throw RootfsException(RootfsException.Kind.Storage, "can't read the downloaded tarball", e)
            }
        try {
            sig.init(BcPGPContentVerifierBuilderProvider(), key)
            val sha256 = MessageDigest.getInstance("SHA-256")
            val total = channel.size()
            val input = Channels.newInputStream(channel)
            val buf = ByteArray(1 shl 16)
            var done = 0L
            var lastReport = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sig.update(buf, 0, n)
                sha256.update(buf, 0, n)
                done += n
                if (done - lastReport >= 16 shl 20) {
                    lastReport = done
                    ensureActive()
                    progress(done, total)
                }
            }
            if (done == 0L || !sig.verify()) {
                throw RootfsException(RootfsException.Kind.Mismatch, "the tarball doesn't match its signature")
            }
            ensureActive()
            return VerifiedRootfs(channel, sha256.digest(), done)
        } catch (e: Throwable) {
            channel.close()
            if (e is PGPException) throw RootfsException(RootfsException.Kind.Signature, "the signature can't be checked", e)
            throw e
        }
    }

    /**
     * Throws as [verify] would for a signature it can't accept (unreadable: [RootfsException.Kind.Signature];
     * refused: [RootfsException.Kind.Signer]), before there's a tarball to check it against. Passing
     * this proves nothing about any bytes: only [verify] does.
     */
    fun check(signature: ByteArray) {
        parse(signature)
    }

    private fun parse(bytes: ByteArray): PGPSignature {
        fun bad(why: String, cause: Throwable? = null): Nothing = throw RootfsException(RootfsException.Kind.Signature, "the signature $why", cause)

        // Readable, but not one this app accepts: never a reason to trust it, but maybe one to update.
        fun refused(why: String): Nothing = throw RootfsException(RootfsException.Kind.Signer, "the signature $why")
        if (bytes.isEmpty()) bad("is empty")
        if (bytes.size > MAX_SIGNATURE_BYTES) bad("is too big to be one")
        val objects =
            try {
                val factory = BcPGPObjectFactory(bytes)
                generateSequence { factory.nextObject() }.take(3).toList()
            } catch (e: Exception) {
                // Bouncy Castle reports malformed packets with assorted runtime exceptions too.
                bad("is malformed", e)
            }
        val list = objects.singleOrNull() as? PGPSignatureList ?: bad("is not a single detached signature")
        if (list.size() != 1) bad("is not a single detached signature")
        val sig = list[0]
        if (sig.version != 4 || sig.signatureType != PGPSignature.BINARY_DOCUMENT) refused("is not a v4 signature over a file")
        if (sig.keyAlgorithm !in RSA || sig.hashAlgorithm !in HASHES) refused("uses an unsupported algorithm")
        if (sig.keyID != key.keyID) refused("is not by Arch Linux ARM's build key")
        sig.hashedSubPackets?.issuerFingerprint?.let { if (!it.fingerprint.contentEquals(key.fingerprint)) refused("is not by Arch Linux ARM's build key") }
        if (sig.creationTime.before(key.creationTime)) refused("predates its key")
        return sig
    }

    private companion object {
        val RSA = setOf(PublicKeyAlgorithmTags.RSA_GENERAL)
        val HASHES = setOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512)
    }
}

/**
 * A tarball whose signature checked out, held open. Only [RootfsVerifier] makes these, and
 * [RootfsExtractor] only takes these: an unverified file has no way into the unpacker.
 */
class VerifiedRootfs internal constructor(
    private val channel: FileChannel,
    private val digest: ByteArray,
    /** How many bytes were checked. */
    val length: Long,
) : Closeable {
    /** SHA-256 of the checked bytes, lowercase hex. */
    val sha256: String = Hex.toHexString(digest)

    /** The checked bytes from the start. The stream doesn't close the file; [close] does. */
    fun open(): InputStream {
        channel.position(0)
        return object : FilterInputStream(Channels.newInputStream(channel)) {
            override fun close() = Unit
        }
    }

    internal fun matches(other: ByteArray) = other.contentEquals(digest)

    override fun close() = channel.close()
}
