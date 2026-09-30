package com.lekaspos.ui.settings

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.time.DateText
import com.lekaspos.core.time.Days
import com.lekaspos.data.backup.BackupFiles
import com.lekaspos.data.backup.Restore
import com.lekaspos.data.db.Meta
import com.lekaspos.domain.backup.BackupService
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.trackedBy
import com.lekaspos.ui.sell.visible
import java.io.File
import java.io.InputStream
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Backup & restore (D-044): the backups kept on this phone (a daily automatic one,
 * one before each upgrade or restore), back up now, save or share a backup file (USB, Drive,
 * WhatsApp …), and restore — as this till (the old phone is gone) or as a new till.
 */
class BackupActivity : ScreenActivity() {

    private lateinit var header: TextView
    private lateinit var empty: TextView
    private val tz = TimeZone.getDefault()

    private val adapter = RowAdapter<BackupService.Entry>(
        bind = { h, e ->
            val hd = e.header
            val title = hd?.let { DateText.dateTime(it.createdAt, tz) } ?: e.file.name
            val sub = listOfNotNull(
                getString(reasonLabel(e.file.name)),
                hd?.storeName,
                hd?.let { getString(R.string.backup_counts, it.sales, it.products) },
                Formatter.formatShortFileSize(this, e.file.length()),
            ).joinToString(" · ")
            h.set(title, sub)
        },
        onClick = { e -> confirmRestore(e.header) { e.file.inputStream() } },
        onLongClick = { e -> deleteBackup(e) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.backup_title), R.layout.list_header) ?: return
        header = v.findViewById(R.id.list_header)
        header.setText(R.string.backup_help)
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.backup_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        addAction(R.drawable.ic_add, R.string.backup_now) { backupNow() }
        lateinit var more: ImageButton
        more = addAction(R.drawable.ic_more, R.string.sell_menu) { menu(more) }
        guard(Perm.SETTINGS)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PICK_FOLDER, false)) pickFolder()
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        launchUi {
            val p = graph.backups.refreshProtection()
            header.text = listOfNotNull(safetyText(p), getString(R.string.backup_help)).joinToString("\n\n")
            val items = graph.backups.list()
            adapter.submit(items)
            empty.visible(items.isEmpty())
        }
    }

    /** Where the data is safe, or why it is not (D-048). */
    private fun safetyText(p: BackupService.Protection): String? {
        val lines = ArrayList<String>(3)
        when (p.state) {
            BackupService.Protection.State.DAMAGED -> lines.add(getString(R.string.safety_damaged, p.damage ?: ""))
            BackupService.Protection.State.AT_RISK -> lines.add(getString(R.string.safety_at_risk_short))
            BackupService.Protection.State.PROTECTED ->
                p.lastOffPhone?.let { lines.add(getString(R.string.safety_protected, DateText.dateTime(it, tz))) }
            BackupService.Protection.State.NO_DATA -> Unit
        }
        p.folderName?.let { lines.add(getString(R.string.backup_folder_on, it.ifBlank { "-" })) }
        p.folderError?.let { lines.add(getString(R.string.backup_folder_error, it)) }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** The system folder picker: an SD card, USB drive or any folder outside the app. */
    private fun pickFolder() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_FOLDER)
        } catch (e: ActivityNotFoundException) {
            Dialogs.message(this, null, getString(R.string.backup_folder_failed))
        }
    }

    private fun copyToFolder() {
        launchUi {
            toast(R.string.backup_working)
            toast(if (graph.backups.copyToFolderNow()) R.string.backup_folder_done else R.string.backup_folder_failed)
            reload()
        }
    }

    /** A readable name for the picked folder (e.g. "Backups" on the SD card). */
    private fun folderName(tree: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun reasonLabel(name: String): Int = when {
        name.startsWith(BackupService.AUTO) -> R.string.backup_reason_auto
        name.startsWith(BackupService.MANUAL) -> R.string.backup_reason_manual
        name.startsWith("upgrade-") -> R.string.backup_reason_upgrade
        name.startsWith(Restore.REASON_REPLACED) -> R.string.backup_reason_replaced
        else -> R.string.backup_reason_other
    }

    private fun backupNow() {
        launchUi {
            toast(R.string.backup_working)
            graph.backups.backupNow()
            toast(R.string.backup_done)
            reload()
        }
    }

    private fun menu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = ArrayList(listOf(R.string.backup_folder_pick))
        if (graph.backups.protection.value.folderName != null) items += listOf(R.string.backup_folder_copy, R.string.backup_folder_off)
        items += listOf(R.string.backup_save_file, R.string.backup_share, R.string.backup_restore_file)
        for ((i, res) in items.withIndex()) m.menu.add(0, res, i, res)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.string.backup_save_file -> {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(MIME).putExtra(Intent.EXTRA_TITLE, fileName()),
                        REQ_SAVE,
                    )
                }
                R.string.backup_share -> share()
                R.string.backup_folder_pick -> pickFolder()
                R.string.backup_folder_copy -> copyToFolder()
                R.string.backup_folder_off -> launchUi {
                    graph.backups.setFolder(null, null)
                    reload()
                }
                R.string.backup_restore_file -> {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_OPEN)
                }
            }
            true
        }
        m.show()
    }

    private fun fileName(): String {
        val day = Days.epochDay(System.currentTimeMillis(), tz)
        return "lekaspos-backup-${com.lekaspos.core.time.DateText.isoDate(day)}${BackupFiles.EXT}"
    }

    private fun share() {
        launchUi {
            toast(R.string.backup_working)
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, fileName())
            }
            withContext(Dispatchers.IO) { file.outputStream() }.use { graph.backups.export(it) }
            val uri = FileProvider.getUriForFile(this@BackupActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri)
            startActivity(Intent.createChooser(send, getString(R.string.backup_share)))
        }
    }

    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_FOLDER -> launchUi {
                val name = withContext(Dispatchers.IO) { folderName(uri) }
                graph.backups.setFolder(uri, name)
                val copied = graph.backups.copyToFolderNow()
                toast(if (copied) R.string.backup_folder_done else R.string.backup_folder_failed)
                reload()
            }
            REQ_SAVE -> launchUi {
                toast(R.string.backup_working)
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt") }?.use { graph.backups.export(it) }
                toast(R.string.backup_saved)
            }
            REQ_OPEN -> launchUi {
                val open = { contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot read the file") }
                val h = graph.backups.header(open)
                if (h == null) {
                    Dialogs.message(this@BackupActivity, null, getString(R.string.backup_not_a_backup))
                } else {
                    confirmRestore(h, open)
                }
            }
        }
    }

    /** Explains what a restore does and lets the user choose how this phone continues. */
    private fun confirmRestore(h: BackupFiles.Header?, open: () -> InputStream) {
        if (h == null) {
            Dialogs.message(this, null, getString(R.string.backup_not_a_backup))
            return
        }
        requireAccess(Perm.SETTINGS) {
            launchUi {
                val (store, device) = graph.db().read { Meta.get(it, Meta.STORE_UUID) to Meta.get(it, Meta.DEVICE_UUID) }
                val lines = ArrayList<String>()
                lines.add(getString(R.string.backup_restore_what, DateText.dateTime(h.createdAt, tz), h.storeName ?: "-", h.sales, h.products))
                if (h.storeUuid != store) lines.add(getString(R.string.backup_other_store))
                if (graph.db().syncEnabled) lines.add(getString(R.string.backup_restore_sync))
                lines.add(getString(R.string.backup_restore_how))
                val d = AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.backup_restore_title)
                    .setMessage(lines.joinToString("\n\n"))
                    .setPositiveButton(if (h.deviceUuid == device) R.string.backup_mode_same else R.string.backup_mode_replace) { _, _ -> restore(open, Restore.Mode.REPLACE) }
                    .setNeutralButton(R.string.backup_mode_new) { _, _ -> restore(open, Restore.Mode.NEW_DEVICE) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                d.trackedBy(this@BackupActivity)
            }
        }
    }

    private fun restore(open: () -> InputStream, mode: Restore.Mode) {
        launchUi {
            toast(R.string.backup_checking)
            graph.backups.stageRestore(open, mode)
            AlertDialog.Builder(this@BackupActivity)
                .setTitle(R.string.backup_restore_title)
                .setMessage(R.string.backup_restart)
                .setCancelable(false)
                .setPositiveButton(R.string.backup_restart_now) { _, _ -> graph.backups.restart(this@BackupActivity) }
                .setNegativeButton(R.string.cancel) { _, _ -> graph.backups.cancelRestore() }
                .show()
                .trackedBy(this@BackupActivity)
        }
    }

    private fun deleteBackup(e: BackupService.Entry) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.backup_delete_confirm), getString(R.string.delete)) {
            launchUi {
                graph.backups.delete(e.file)
                reload()
            }
        }
    }

    companion object {
        private const val REQ_SAVE = 31
        private const val REQ_OPEN = 32
        private const val REQ_FOLDER = 33
        const val EXTRA_PICK_FOLDER = "pick_folder"
        private const val MIME = "application/octet-stream"
    }
}
