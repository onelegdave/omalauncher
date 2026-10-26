package dev.omalauncher.linux

import dev.omalauncher.LauncherData
import dev.omalauncher.linux.RawHttpServer.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class RootfsInstallerTest {
    @Test fun insufficientSpaceBeforeExtractionKeepsTheVerifiedCacheAndOldRootfs() {
        rootfs.mkdirs()
        val old = File(rootfs, "old").apply { writeText("kept") }
        val m = mirror()
        val noSpace = java.io.IOException("ENOSPC")
        val installer = RootfsInstaller(
            files, { false }, { forgets++ }, LauncherData::deleteTree,
            beforeExtract = { throw noSpace },
        )
        assertTrue(assertThrows(java.io.IOException::class.java) { installer.install(source(m), extractor()) } === noSpace)
        assertEquals("kept", old.readText())
        assertTrue(File(cache, ROOTFS_TARBALL).exists())
        assertFalse(installer.activated())
        assertEquals(0, forgets)
    }

    @Test fun cancellationBetweenExtractionAndActivationKeepsTheOldRootfs() {
        rootfs.mkdirs()
        val old = File(rootfs, "old").apply { writeText("kept") }
        val installer = installer
        var checks = 0
        assertThrows(CancellationException::class.java) {
            installer.install(source(mirror()), extractor()) {
                if (++checks == 3) throw CancellationException("stop before activation")
            }
        }
        assertEquals("kept", old.readText())
        assertFalse(installer.staging.exists())
        assertFalse(installer.activated())
        assertEquals(0, forgets)
    }

    @get:Rule val tmp = TemporaryFolder()

    private val signer = TestSigner.trusted
    private val tarball = TarFixture.minimalRootfs().file("./etc/hostname", "alarm").gz()

    private lateinit var files: File
    private lateinit var cache: File
    private lateinit var external: File
    private lateinit var widgets: File
    private val servers = ArrayList<RawHttpServer>()

    /** Stand-ins for the journal: what was recorded about the rootfs, and whether setup finished. */
    private var setUp = false
    private var forgets = 0

    @Before fun setUpDirs() {
        files = tmp.newFolder("files")
        cache = tmp.newFolder("cache")
        external = tmp.newFolder("external")
        // Launcher data outside the rootfs; it must survive everything.
        widgets = File(files, "widgets").apply { mkdirs() }
        File(widgets, "clock.owl").writeText("widget")
    }

    @After fun stop() = servers.forEach { it.close() }

    private val installer
        get() =
            RootfsInstaller(
                files,
                setUp = { setUp },
                forgetOldRootfs = { forgets++ },
                deleteTree = LauncherData::deleteTree,
            )

    private fun mirror(
        archive: ByteArray = tarball,
        sig: ByteArray = signer.sign(tarball),
    ) = RawHttpServer { req ->
        when {
            req.path.endsWith(".sig") -> Response(200, sig)
            else -> Response(200, archive)
        }
    }.also { servers += it }

    private fun source(
        server: RawHttpServer? = null,
        pushed: File? = null,
        ensureActive: () -> Unit = {},
    ) = RootfsSource(
        cache,
        pushed,
        signer.verifier(),
        RootfsDownloader(cache, allowed = { it.host == "127.0.0.1" }, ensureActive = ensureActive),
        listOfNotNull(server?.let { it.base + "/os/" }),
        ensureActive,
    )

    private fun extractor(ensureActive: () -> Unit = {}) = RootfsExtractor(ensureActive = ensureActive)

    private val rootfs get() = File(files, "arch")
    private val staging get() = File(files, "arch.staging")

    private fun assertLauncherDataIntact() = assertEquals("widget", File(widgets, "clock.owl").readText())

    @Test fun installsAndActivates() {
        installer.install(source(mirror()), extractor())
        assertEquals("alarm", File(rootfs, "etc/hostname").readText())
        assertEquals("usr/bin", Files.readSymbolicLink(File(rootfs, "bin").toPath()).toString())
        assertFalse(staging.exists())
        assertTrue(installer.activated())
        assertEquals(1, forgets)
        // The download goes once the rootfs is in place.
        assertEquals(0, cache.list()!!.size)
        assertLauncherDataIntact()
    }

    @Test fun replacesAnIncompleteEarlierRootfs() {
        File(rootfs, "half/unpacked").mkdirs()
        File(rootfs, "half/unpacked/file").writeText("old")
        installer.install(source(mirror()), extractor())
        assertFalse(File(rootfs, "half").exists())
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
    }

    @Test fun neverReplacesASetUpSystem() {
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/notes").writeText("mine")
        setUp = true
        val m = mirror()
        assertThrows(IllegalStateException::class.java) { installer.install(source(m), extractor()) }
        assertEquals("mine", File(rootfs, "root/notes").readText())
        assertTrue(m.requests.isEmpty())
        assertEquals(0, forgets)
    }

    @Test fun aFailedCheckChangesNothing() {
        File(rootfs, "half").mkdirs()
        val m = mirror(sig = TestSigner.other.sign(tarball))
        assertThrows(RootfsException::class.java) { installer.install(source(m), extractor()) }
        assertTrue(File(rootfs, "half").exists())
        assertFalse(staging.exists())
        assertEquals(0, forgets)
        assertFalse(installer.activated())
        assertLauncherDataIntact()
    }

    @Test fun aSignedButUnusableTarballChangesNothingAndIsntDownloadedAgain() {
        val bad = TarFixture.minimalRootfs().file("../outside").gz()
        val m = mirror(bad, signer.sign(bad))
        repeat(2) {
            val e = assertThrows(RootfsException::class.java) { installer.install(source(m), extractor()) }
            assertEquals(RootfsException.Kind.Format, e.kind)
            assertFalse(rootfs.exists())
            assertFalse(staging.exists())
            assertFalse(File(tmp.root, "outside").exists())
            assertEquals(0, forgets)
            // Kept: the same signed bytes would fail the same way, so fetching them again can't help.
            assertArrayEquals(bad, File(cache, ROOTFS_TARBALL).readBytes())
        }
        // The retry re-checked it against a fresh signature, without downloading it again.
        assertEquals(1, m.requests.count { it.path.endsWith(ROOTFS_TARBALL) })
        assertEquals(2, m.requests.count { it.path.endsWith(".sig") })
        // Once a new release is out, the kept one no longer matches the signature and a fresh one comes.
        installer.install(source(mirror()), extractor())
        assertTrue(installer.activated())
    }

    @Test fun aTarballChangedWhileUnpackingIsDroppedAndFetchedAgain() {
        val tampering =
            RootfsInstaller(files, setUp = { setUp }, forgetOldRootfs = { forgets++ }, deleteTree = LauncherData::deleteTree) { _, step, _ ->
                // After the check, before unpacking: gzip's MTIME field, so it still unpacks, but not as the checked bytes.
                if (step == "Starting") {
                    java.io.RandomAccessFile(File(cache, ROOTFS_TARBALL), "rw").use {
                        it.seek(4)
                        it.write(0x7f)
                    }
                }
            }
        val m = mirror()
        val e = assertThrows(RootfsException::class.java) { tampering.install(source(m), extractor()) }
        assertEquals(RootfsException.Kind.Changed, e.kind)
        assertFalse(rootfs.exists())
        assertFalse(staging.exists())
        assertEquals(0, forgets)
        assertEquals(0, cache.list()!!.size)
        installer.install(source(m), extractor())
        assertEquals(2, m.requests.count { it.path.endsWith(ROOTFS_TARBALL) })
        assertTrue(installer.activated())
    }

    @Test fun aPushedButUnusableTarballLeavesTheUsersFilesAndNoCopy() {
        val bad = TarFixture.minimalRootfs().file("../outside").gz()
        File(external, ROOTFS_TARBALL).writeBytes(bad)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(signer.sign(bad))
        val e = assertThrows(RootfsException::class.java) { installer.install(source(pushed = external), extractor()) }
        assertEquals(RootfsException.Kind.Format, e.kind)
        assertArrayEquals(bad, File(external, ROOTFS_TARBALL).readBytes())
        assertTrue(File(external, "$ROOTFS_TARBALL.sig").exists())
        // The private copy is made afresh by every try, so it doesn't stay around taking 830 MB.
        assertEquals(0, cache.list()!!.size)
    }

    @Test fun aFailedRenameIsReportedAndRecordsNothing() {
        // A rootfs that can't be deleted (a stand-in for a file that resists) blocks the rename.
        File(rootfs, "stuck").mkdirs()
        val stubborn =
            RootfsInstaller(files, setUp = { setUp }, forgetOldRootfs = { forgets++ }, deleteTree = { if (it != rootfs) LauncherData.deleteTree(it) })
        val e = assertThrows(RootfsException::class.java) { stubborn.install(source(mirror()), extractor()) }
        assertEquals(RootfsException.Kind.Storage, e.kind)
        assertFalse(stubborn.activated())
        assertFalse(staging.exists())
        assertTrue(File(rootfs, "stuck").exists())
    }

    @Test fun cancellationLeavesARetryableState() {
        // Cancelled once the tarball was read, before anything was activated.
        val cancel = { throw CancellationException("stopped") }
        assertThrows(CancellationException::class.java) { installer.install(source(mirror()), extractor(cancel)) }
        assertFalse(rootfs.exists())
        assertFalse(staging.exists())
        assertEquals(0, forgets)
        // The verified download stays for the retry, which needs no new download.
        val m = mirror()
        installer.install(source(m), extractor())
        assertEquals(0, m.requests.count { it.path.endsWith(ROOTFS_TARBALL) })
    }

    // Each point the app can die at, and what the next try does.

    @Test fun window1And2PartialOrUnverifiedDownloadsAreResumedOrRechecked() {
        File(cache, ROOTFS_TARBALL).writeBytes(tarball)
        val m = mirror()
        installer.install(source(m), extractor())
        assertEquals(0, m.requests.count { it.path.endsWith(ROOTFS_TARBALL) })
        assertTrue(installer.activated())
    }

    @Test fun windows4And5LeftoverStagingIsDiscarded() {
        File(staging, "usr/bin").mkdirs()
        File(staging, "usr/bin/planted").writeText("x")
        File(staging, ROOTFS_TOKEN).writeText("0".repeat(32))
        installer.install(source(mirror()), extractor())
        assertFalse(File(rootfs, "usr/bin/planted").exists())
        assertTrue(installer.activated())
    }

    @Test fun window5StagingThatIsALinkIsRemovedNotFollowed() {
        val elsewhere = tmp.newFolder("elsewhere")
        File(elsewhere, "keep").writeText("keep")
        Files.createSymbolicLink(staging.toPath(), elsewhere.toPath())
        installer.install(source(mirror()), extractor())
        assertEquals("keep", File(elsewhere, "keep").readText())
        assertEquals(1, elsewhere.list()!!.size)
        assertTrue(installer.activated())
    }

    @Test fun window6OldRootGoneStagingNotRenamed() {
        installer.install(source(mirror()), extractor())
        // As if the app died after deleting the old rootfs and before the rename: only staging exists.
        Files.move(rootfs.toPath(), staging.toPath())
        assertFalse(installer.activated())
        installer.install(source(mirror()), extractor())
        assertTrue(installer.activated())
        assertFalse(staging.exists())
    }

    @Test fun window7ActivatedButNotRecordedIsKeptWithoutDownloading() {
        installer.install(source(mirror()), extractor())
        File(rootfs, "root/.bash_history").writeText("kept")
        File(cache, ROOTFS_TARBALL).writeBytes(tarball) // as if left behind
        val m = mirror()
        installer.install(source(m), extractor())
        assertTrue(m.requests.isEmpty())
        assertEquals("kept", File(rootfs, "root/.bash_history").readText())
        assertEquals(1, forgets)
        assertEquals(0, cache.list()!!.size)
    }

    @Test fun aTokenWithoutItsRecordIsNotAnActivation() {
        // An older launcher's half-unpacked tree can't know the random token.
        File(rootfs, "usr").mkdirs()
        File(rootfs, ROOTFS_TOKEN).writeText("0123456789abcdef0123456789abcdef")
        assertFalse(installer.activated())
        File(files, "rootfs-activation.json").writeText("""{"version":1,"token":"fedcba9876543210fedcba9876543210","sha256":"x"}""")
        assertFalse(installer.activated())
        installer.install(source(mirror()), extractor())
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
        assertTrue(installer.activated())
    }

    @Test fun window9ASetUpSystemIsNeverTouchedEvenIfActivatedEarlier() {
        installer.install(source(mirror()), extractor())
        setUp = true
        assertThrows(IllegalStateException::class.java) { installer.install(source(mirror()), extractor()) }
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
    }

    @Test fun forgetsOldRecordsOnlyRightBeforeTheSwitch() {
        val order = ArrayList<String>()
        val tracking =
            RootfsInstaller(
                files,
                setUp = { setUp },
                forgetOldRootfs = { order += "forget:" + (if (staging.exists()) "staged" else "none") + "," + (if (rootfs.exists()) "old" else "noold") },
                deleteTree = LauncherData::deleteTree,
            )
        File(rootfs, "old").mkdirs()
        tracking.install(source(mirror()), extractor())
        assertEquals(listOf("forget:staged,old"), order)
    }

    @Test fun aPushedPairInstallsAndStays() {
        File(external, ROOTFS_TARBALL).writeBytes(tarball)
        File(external, "$ROOTFS_TARBALL.sig").writeBytes(signer.sign(tarball))
        installer.install(source(pushed = external), extractor())
        assertTrue(installer.activated())
        assertArrayEquals(tarball, File(external, ROOTFS_TARBALL).readBytes())
        assertTrue(File(external, "$ROOTFS_TARBALL.sig").exists())
        assertEquals(0, cache.list()!!.size)
    }

    private val activation get() = File(files, "rootfs-activation.json")

    @Test fun removalDeletesTheRootfsAndOnlyItsOwnFiles() {
        installer.install(source(mirror()), extractor())
        File(staging, "x").mkdirs()
        assertEquals(RootfsInstaller.Removal.Done, installer.remove())
        assertFalse(rootfs.exists())
        assertFalse(staging.exists())
        assertFalse(activation.exists())
        assertLauncherDataIntact()
        // Nothing to do is fine: the leftover check on every start calls it.
        assertEquals(RootfsInstaller.Removal.Done, installer.remove())
    }

    // Removing Linux as Bootstrap does: these files stand in for its journal and its record that an install was started.
    private val journalFile get() = File(files, "rootfs-steps.json")
    private val choiceFile get() = File(files, "setup-agents")

    private fun installedSystem() {
        installer.install(source(mirror()), extractor())
        journalFile.writeText("set up")
        choiceFile.writeText("claude\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "root/.agent-credentials").writeText("secret")
    }

    private fun forgetLikeBootstrap() {
        check(choiceFile.delete())
        check(journalFile.delete())
    }

    @Test fun aRemovalThatFailsPartwayNeverLeavesAnActivatedLookingTree() {
        installer.install(source(mirror()), extractor())
        File(staging, "leftover").mkdirs()
        val order = ArrayList<String>()
        // Staging resists entirely; the rootfs loses usr/ and then a file resists, its token still there.
        val failing =
            RootfsInstaller(files, setUp = { setUp }, forgetOldRootfs = { forgets++ }, deleteTree = { tree ->
                order += tree.name + (if (activation.exists()) ":recorded" else ":forgotten")
                if (tree == rootfs) LauncherData.deleteTree(File(rootfs, "usr"))
                throw java.io.IOException("a file resisted")
            })
        val removal = failing.remove { forgets++ }
        assertTrue(removal is RootfsInstaller.Removal.Leftovers)
        assertEquals(2, forgets)
        // The record went first, and deleting went on to the rootfs although staging failed.
        assertEquals(listOf("arch.staging:forgotten", "arch:forgotten"), order)
        assertTrue(File(rootfs, ROOTFS_TOKEN).exists())
        assertFalse(File(rootfs, "usr").exists())
        assertFalse(installer.activated())
        // So the next install replaces the remains instead of keeping them.
        val m = mirror()
        installer.install(source(m), extractor())
        assertEquals(1, m.requests.count { it.path.endsWith(ROOTFS_TARBALL) })
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
        assertFalse(File(rootfs, "leftover").exists())
        assertTrue(installer.activated())
    }

    @Test fun aRecordThatCantBeDeletedStopsRemovalBeforeAnythingIsDeleted() {
        installer.install(source(mirror()), extractor())
        File(staging, "leftover").mkdirs()
        // Something that won't go: a non-empty directory where the record is.
        activation.delete()
        File(activation, "stuck").mkdirs()
        val removal = installer.remove { forgets++ }
        assertTrue(removal is RootfsInstaller.Removal.Kept)
        assertEquals(RootfsException.Kind.Storage, ((removal as RootfsInstaller.Removal.Kept).cause as RootfsException).kind)
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
        assertTrue(File(rootfs, ROOTFS_TOKEN).isFile)
        assertTrue(File(staging, "leftover").isDirectory)
        // The caller's records weren't touched either.
        assertEquals(1, forgets)
        assertLauncherDataIntact()
    }

    @Test fun aRemovalThatCantInvalidateKeepsTheSystemItsRecordsAndItsOwner() {
        installedSystem()
        // Deleting the record fails while it's still readable: the files dir is read-only.
        assertTrue(files.setWritable(false))
        val removal =
            try {
                installer.remove(::forgetLikeBootstrap)
            } finally {
                files.setWritable(true)
            }
        assertTrue(removal is RootfsInstaller.Removal.Kept)
        assertTrue(installer.activated())
        // Bootstrap reports it and stays Installed: journal and agent choice stay, and so does everything in the system.
        assertTrue(journalFile.exists() && choiceFile.exists())
        assertEquals("secret", File(rootfs, "root/.agent-credentials").readText())
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
        // The journal still owns it, so no install can adopt or replace it meanwhile.
        setUp = true
        assertThrows(IllegalStateException::class.java) { installer.install(source(mirror()), extractor()) }
        assertEquals("secret", File(rootfs, "root/.agent-credentials").readText())
    }

    @Test fun aRemovalWhoseRecordsCantBeForgottenDeletesNothing() {
        installedSystem()
        val removal = installer.remove { throw java.io.IOException("storage full") }
        assertTrue(removal is RootfsInstaller.Removal.Kept)
        assertEquals("secret", File(rootfs, "root/.agent-credentials").readText())
        assertTrue(journalFile.exists() && choiceFile.exists())
    }

    @Test fun aRemovalForgetsTheSystemBeforeDeletingIt() {
        installedSystem()
        val order = ArrayList<String>()
        val tracking =
            RootfsInstaller(files, setUp = { setUp }, forgetOldRootfs = { forgets++ }, deleteTree = { tree ->
                order += tree.name + (if (journalFile.exists()) ":setup" else ":forgotten")
                LauncherData.deleteTree(tree)
            })
        assertEquals(RootfsInstaller.Removal.Done, tracking.remove { order += "forget:" + (if (activation.exists()) "recorded" else "unrecorded"); forgetLikeBootstrap() })
        // Killed while deleting, the next start finds nothing set up: it finishes deleting, it doesn't run a half-deleted system.
        assertEquals(listOf("forget:unrecorded", "arch.staging:forgotten", "arch:forgotten"), order)
        assertFalse(rootfs.exists())
    }

    @Test fun anInstallWaitsForALeftoverRemovalToFinish() {
        // What a removal left: the next start deletes it, and here deleting is slow.
        installedSystem()
        activation.delete()
        val deleting = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        var blocked = false
        val slow =
            RootfsInstaller(files, setUp = { setUp }, forgetOldRootfs = { forgets++ }, deleteTree = { tree ->
                if (tree == rootfs && !blocked) {
                    blocked = true
                    deleting.countDown()
                    proceed.await()
                }
                LauncherData.deleteTree(tree)
            })
        var removal: RootfsInstaller.Removal? = null
        val cleanup = Thread { removal = slow.remove() }.apply { start() }
        assertTrue(deleting.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // The user installs right away, from a finished download.
        File(cache, ROOTFS_TARBALL).writeBytes(tarball)
        val m = mirror()
        var failure: Throwable? = null
        val install = Thread { runCatching { slow.install(source(m), extractor()) }.onFailure { failure = it } }.apply { start() }
        install.join(500)
        // It hasn't started: nothing fetched, no staging, while the old tree is still being deleted by path.
        assertTrue(install.isAlive)
        assertTrue(m.requests.isEmpty())
        assertFalse(staging.exists())
        proceed.countDown()
        cleanup.join(10_000)
        install.join(10_000)
        assertEquals(RootfsInstaller.Removal.Done, removal)
        assertEquals(null, failure)
        // The new tree is whole and activated; nothing of the old one is in it.
        assertTrue(slow.activated())
        assertTrue(File(rootfs, "usr/bin/bash").isFile)
        assertEquals("alarm", File(rootfs, "etc/hostname").readText())
        assertFalse(File(rootfs, "root/.agent-credentials").exists())
    }
}
