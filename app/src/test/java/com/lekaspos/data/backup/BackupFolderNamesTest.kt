package com.lekaspos.data.backup

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 2026-10 review: the owner's own saved backups ("lekaspos-backup-<date>") counted as daily copies,
 * sorted above them by name, and the daily pruning deleted the daily copies and then the owner's files.
 */
class BackupFolderNamesTest {

    @Test
    fun onlyTheDailyCopiesNamesArePruned() {
        assertTrue(BackupFolder.isOurs("lekaspos-2026-10-03-0915.lekasbak"))
        assertTrue(BackupFolder.isOurs("lekaspos-2026-10-03-0915 (1).lekasbak")) // a folder app's "name taken"
        assertFalse(BackupFolder.isOurs("lekaspos-backup-2026-10-03.lekasbak")) // saved by hand
        assertFalse(BackupFolder.isOurs("lekaspos-2026-10-03-0915.lekasbak.part"))
        assertFalse(BackupFolder.isOurs("lekaspos-notes.lekasbak"))
        assertFalse(BackupFolder.isOurs("my lekaspos-2026-10-03-0915.lekasbak"))
    }
}
