package dev.omalauncher.linux

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPSignature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class RootfsSignatureTest {
    @get:Rule val tmp = TemporaryFolder()

    private val signer = TestSigner.trusted
    private val data = "an archive's compressed bytes".repeat(5000).toByteArray()

    private fun file(bytes: ByteArray = data) = tmp.newFile().apply { writeBytes(bytes) }

    private fun rejects(
        kind: RootfsException.Kind,
        archive: File,
        signature: ByteArray,
        verifier: RootfsVerifier = signer.verifier(),
    ) {
        val e = assertThrows(RootfsException::class.java) { verifier.verify(archive, signature) }
        assertEquals(e.message, kind, e.kind)
    }

    @Test fun acceptsTheAuthorizedKeysSignatureOverTheExactBytes() {
        signer.verifier().verify(file(), signer.sign(data)).use { v ->
            assertEquals(data.size.toLong(), v.length)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, v.sha256)
            assertArrayEquals(data, v.open().readBytes())
        }
    }

    @Test fun acceptsSha256AndSha384() {
        for (hash in listOf(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384)) signer.verifier().verify(file(), signer.sign(data, hash)).close()
    }

    @Test fun rejectsAChangedByte() {
        val changed = data.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        rejects(RootfsException.Kind.Mismatch, file(changed), signer.sign(data))
    }

    @Test fun rejectsTruncatedOrEmptyArchives() {
        rejects(RootfsException.Kind.Mismatch, file(data.copyOf(data.size - 1)), signer.sign(data))
        rejects(RootfsException.Kind.Mismatch, file(ByteArray(0)), signer.sign(data))
        rejects(RootfsException.Kind.Mismatch, file(data + 0), signer.sign(data))
    }

    @Test fun rejectsMissingEmptyTruncatedMalformedOrModifiedSignatures() {
        val sig = signer.sign(data)
        rejects(RootfsException.Kind.Signature, file(), ByteArray(0))
        rejects(RootfsException.Kind.Signature, file(), sig.copyOf(sig.size / 2))
        rejects(RootfsException.Kind.Signature, file(), "<html>captive portal</html>".toByteArray())
        rejects(RootfsException.Kind.Signature, file(), ByteArray(MAX_SIGNATURE_BYTES + 1))
        // Two signatures where one is expected.
        rejects(RootfsException.Kind.Signature, file(), sig + sig)
        // A flipped bit in the signature value itself: well-formed, but it no longer verifies.
        val flipped = sig.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        rejects(RootfsException.Kind.Mismatch, file(), flipped)
    }

    @Test fun rejectsArmoredSignatures() {
        val armored = "-----BEGIN PGP SIGNATURE-----\n\n" + java.util.Base64.getMimeEncoder().encodeToString(signer.sign(data)) + "\n-----END PGP SIGNATURE-----\n"
        rejects(RootfsException.Kind.Signature, file(), armored.toByteArray())
    }

    @Test fun rejectsAValidSignatureByAnotherKey() {
        // Readable, so not reported as damage: a refused signer, which only an app update could change.
        rejects(RootfsException.Kind.Signer, file(), TestSigner.other.sign(data))
    }

    @Test fun forgedIssuerMetadataConfersNoTrust() {
        // Another key's signature claiming the trusted key's ID gets past the metadata check, and fails the cryptography.
        val forged = TestSigner.other.sign(data, claimIssuer = signer.pair.keyID)
        rejects(RootfsException.Kind.Mismatch, file(), forged)
    }

    @Test fun rejectsOtherSignatureTypes() {
        rejects(RootfsException.Kind.Signer, file(), signer.sign(data, type = PGPSignature.CANONICAL_TEXT_DOCUMENT))
    }

    @Test fun rejectsWeakHashes() {
        rejects(RootfsException.Kind.Signer, file(), signer.sign(data, HashAlgorithmTags.SHA1))
    }

    @Test fun theKeyAssetMustBeThePinnedKey() {
        assertThrows(IllegalArgumentException::class.java) { RootfsVerifier(signer.publicRing, TestSigner.other.fingerprint) }
        assertThrows(IllegalArgumentException::class.java) { RootfsVerifier(signer.publicRing + TestSigner.other.publicRing, signer.fingerprint) }
        // A short key ID is not a fingerprint.
        assertThrows(IllegalArgumentException::class.java) { RootfsVerifier(signer.publicRing, signer.fingerprint.takeLast(16)) }
    }

    @Test fun theHandleKeepsTheVerifiedBytesWhenThePathIsReplaced() {
        val archive = file()
        signer.verifier().verify(archive, signer.sign(data)).use { v ->
            val swapped = tmp.newFile().apply { writeBytes("something else".toByteArray()) }
            check(swapped.renameTo(archive))
            assertArrayEquals(data, v.open().readBytes())
        }
    }

    // The real trust anchor and a real upstream signature.

    private val assetFile = File("src/main/assets/$ROOTFS_SIGNER_ASSET")
    private val upstreamSig = File(javaClass.getResource("/rootfs/upstream-2026-08-05.tar.gz.sig")!!.toURI())

    @Test fun theBundledKeyIsTheBuildSystemKey() {
        val bytes = assetFile.readBytes()
        // Exported with gpg --export --export-options export-minimal 68B3537F39A313B3E574D06777193F152BDBE6A6! from
        // archlinuxarm/archlinuxarm-keyring@91e6b11698f8df66042d56aaa56fbe9c9263847d; see docs/ARCHITECTURE.md.
        assertEquals(
            "d114c6e8610068d8972c0cd4b46bbc3d60617b5a96e95b59f86fcb5a9d0d6d1a",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        RootfsVerifier(bytes) // checks the fingerprint, version, algorithm, expiry and revocation
    }

    @Test fun theRealUpstreamSignatureIsSupportedAndBindsItsBytes() {
        // A v4 RSA/SHA-512 binary signature by the primary key: parsed and accepted up to the cryptographic check,
        // which these bytes (not the 829 MB tarball) fail.
        rejects(RootfsException.Kind.Mismatch, file(), upstreamSig.readBytes(), RootfsVerifier(assetFile.readBytes()))
    }

    @Test fun theEarlyCheckRefusesWhatVerifyRefusesAndAcceptsNothingByItself() {
        val verifier = signer.verifier()
        fun kind(sig: ByteArray) = runCatching { verifier.check(sig) }.exceptionOrNull()?.let { (it as RootfsException).kind }
        assertEquals(RootfsException.Kind.Signature, kind("<html>captive portal</html>".toByteArray()))
        assertEquals(RootfsException.Kind.Signature, kind(ByteArray(0)))
        assertEquals(RootfsException.Kind.Signer, kind(TestSigner.other.sign(data)))
        assertEquals(RootfsException.Kind.Signer, kind(signer.sign(data, HashAlgorithmTags.SHA1)))
        assertEquals(RootfsException.Kind.Signer, kind(signer.sign(data, type = PGPSignature.CANONICAL_TEXT_DOCUMENT)))
        // The real upstream signature passes it with the bundled key; only verify() can then accept bytes.
        RootfsVerifier(assetFile.readBytes()).check(upstreamSig.readBytes())
        val good = signer.sign(data)
        verifier.check(good)
        rejects(RootfsException.Kind.Mismatch, file(data.copyOf().also { it[0] = 1 }), good)
    }

    @Test fun testKeysCantStandInForTheBundledOne() {
        rejects(RootfsException.Kind.Signer, file(), signer.sign(data), RootfsVerifier(assetFile.readBytes()))
        try {
            RootfsVerifier(signer.publicRing)
            fail("a test key passed for the pinned one")
        } catch (_: IllegalArgumentException) {
        }
    }
}
