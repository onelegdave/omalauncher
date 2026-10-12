package dev.omalauncher.upgrade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StepJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aResetThatCantBeSavedChangesNothing() {
        val file = File(tmp.root, "rootfs-steps.json")
        val journal =
            StepJournal(file).apply {
                mark("rootfs")
                completeSetup()
            }
        // Something that won't be replaced where the new journal is written first.
        File(tmp.root, ".rootfs-steps.json.tmp/stuck").mkdirs()
        assertTrue(runCatching { journal.reset() }.isFailure)
        // Removing Linux then reports a failure and keeps it installed: the journal must still say set up, here and on disk.
        assertTrue(journal.setupComplete)
        assertTrue("rootfs" in journal)
        assertTrue(StepJournal(file).setupComplete)
        File(tmp.root, ".rootfs-steps.json.tmp").deleteRecursively()
        journal.reset()
        assertFalse(journal.setupComplete)
        assertFalse(StepJournal(file).setupComplete)
    }
}
