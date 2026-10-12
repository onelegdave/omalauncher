package dev.omalauncher.linux

import dev.omalauncher.LauncherData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The real image, when a maintainer has one: set OMALAUNCHER_UPSTREAM_ROOTFS to a directory
 * holding ArchLinuxARM-aarch64-latest.tar.gz and its .sig (~830 MB, not in the repository).
 * Verifies it with the bundled key and unpacks it with the app's extractor into that directory;
 * nothing from it is executed. Skipped otherwise, as in CI.
 */
class RootfsUpstreamTest {
    @Test fun verifiesAndUnpacksTheRealImage() {
        val dir = System.getenv("OMALAUNCHER_UPSTREAM_ROOTFS")?.let(::File)
        assumeTrue(dir != null && File(dir, ROOTFS_TARBALL).isFile)
        val verifier = RootfsVerifier(File("src/main/assets/$ROOTFS_SIGNER_ASSET").readBytes())
        val staging = File(dir, "unpacked")
        LauncherData.deleteTree(staging)
        check(staging.mkdir())
        val started = System.nanoTime()
        val kept =
            verifier.verify(File(dir!!, ROOTFS_TARBALL), File(dir, "$ROOTFS_TARBALL.sig").readBytes()).use { verified ->
                println("verified ${verified.length} bytes, sha256 ${verified.sha256}, in ${(System.nanoTime() - started) / 1_000_000} ms")
                val t = System.nanoTime()
                RootfsExtractor().extract(verified, staging).also { println("unpacked $it entries in ${(System.nanoTime() - t) / 1_000_000} ms") }
            }
        assertTrue(kept > 30_000)
        assertEquals("usr/bin", Files.readSymbolicLink(File(staging, "bin").toPath()).toString())
        assertEquals("/run/systemd/resolve/resolv.conf", Files.readSymbolicLink(File(staging, "etc/resolv.conf").toPath()).toString())
        assertTrue(Files.isExecutable(File(staging, "usr/bin/bash").toPath()))
        assertTrue(File(staging, "usr/lib/firmware").list()!!.isEmpty())
        // Kept for comparing with another extractor's result when asked to.
        if (System.getenv("OMALAUNCHER_UPSTREAM_KEEP") == null) LauncherData.deleteTree(staging)
    }
}
