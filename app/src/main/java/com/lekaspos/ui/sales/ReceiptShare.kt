package com.lekaspos.ui.sales

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lekaspos.R
import com.lekaspos.app.LekasApp
import com.lekaspos.core.escpos.MonoImage
import com.lekaspos.core.receipt.PrintLine
import com.lekaspos.domain.print.ReceiptBuilder
import com.lekaspos.hw.printer.Images
import com.lekaspos.hw.printer.ReceiptRenderer
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receipts as a picture (WhatsApp) or PDF, drawn from the stored sale with the same layout as
 * the printer, and a text preview for the screen. Files go to the cache and are shared
 * through FileProvider only.
 */
object ReceiptShare {

    private const val COLS = 32

    fun chooseAndShare(a: Activity, saleId: Long) {
        Dialogs.choose(a, a.getString(R.string.share_title), listOf(a.getString(R.string.share_image), a.getString(R.string.share_pdf))) {
            share(a, saleId, pdf = it == 1)
        }
    }

    fun share(a: Activity, saleId: Long, pdf: Boolean) {
        val app = a.applicationContext
        val graph = LekasApp.graph(app)
        graph.appScope.launch(Dispatchers.Main) {
            try {
                val file = withContext(Dispatchers.IO) { render(app, saleId, pdf) } ?: return@launch
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(if (pdf) "application/pdf" else "image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(file.name, uri)
                val chooser = Intent.createChooser(send, app.getString(R.string.share_title))
                if (a.isFinishing || a.isDestroyed) {
                    app.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    a.startActivity(chooser)
                }
            } catch (e: Exception) {
                Log.e("Sharing a receipt failed", e)
                Toast.makeText(app, app.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Blocking: renders the receipt into cache/shared. */
    private suspend fun render(ctx: Context, saleId: Long, pdf: Boolean): File? {
        val graph = LekasApp.graph(ctx)
        val store = graph.settings.store.value
        val tz = TimeZone.getDefault()
        val doc = graph.db().read { ReceiptBuilder.build(it, saleId, copy = false, store, tz) } ?: return null
        val logoWanted = store.printLogo && Images.hasLogo(ctx)
        val lines = ReceiptBuilder.layout(doc, COLS, store, logoWanted, tz)
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // one shared receipt at a time
        val safe = doc.receiptNo.replace(Regex("[^A-Za-z0-9-]"), "_")
        return if (pdf) {
            val width = COLS * 7 // points: about 80 mm
            val logo = if (logoWanted) Images.logo(ctx, width) else null
            val qr = qrFor(lines, width / 2)
            val renderer = ReceiptRenderer(COLS, width)
            val height = renderer.height(lines, logo, qr)
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
                renderer.draw(page.canvas, lines, logo, qr)
                document.finishPage(page)
                val f = File(dir, "receipt-$safe.pdf")
                FileOutputStream(f).use { document.writeTo(it) }
                f
            } finally {
                document.close()
            }
        } else {
            val width = COLS * 24 // pixels: sharp on phone screens
            val logo = if (logoWanted) Images.logo(ctx, width) else null
            val bmp = ReceiptRenderer(COLS, width).bitmap(lines, logo, qrFor(lines, width / 2))
            try {
                val f = File(dir, "receipt-$safe.png")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                f
            } finally {
                bmp.recycle()
            }
        }
    }

    private fun qrFor(lines: List<PrintLine>, size: Int): MonoImage? =
        lines.firstOrNull { it is PrintLine.Qr }?.let { Images.qr((it as PrintLine.Qr).data, size) }

    /** The receipt as monospace text for an on-screen preview (bold and double-size lines kept). */
    fun preview(lines: List<PrintLine>): CharSequence {
        val sb = SpannableStringBuilder()
        for (l in lines) {
            when (l) {
                is PrintLine.Text -> {
                    val start = sb.length
                    sb.append(l.text)
                    if (l.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (l.big) sb.setSpan(RelativeSizeSpan(2f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append('\n')
                }
                PrintLine.Logo -> Unit
                is PrintLine.Qr -> sb.append("[QR]\n")
                is PrintLine.Feed -> for (i in 0 until l.lines) sb.append('\n')
            }
        }
        return sb
    }
}
