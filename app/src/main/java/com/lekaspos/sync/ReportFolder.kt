package com.lekaspos.sync

import java.io.File

/**
 * The folder "LekasPOS" in the shop's own Google Drive, where the daily sales report goes (D-065).
 * Unlike the sync folder it is visible to the owner. Made by [SyncProviders.reportFolder].
 */
interface ReportFolder {
    /** Writes [file] as [name] ([mime]), replacing the file of that name written before. */
    suspend fun put(name: String, file: File, mime: String)
}
