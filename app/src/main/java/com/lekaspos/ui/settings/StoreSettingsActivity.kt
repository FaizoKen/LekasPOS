package com.lekaspos.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import com.lekaspos.R
import com.lekaspos.core.barcode.ScaleTemplate
import com.lekaspos.core.model.Perm
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.data.settings.StoreSettings
import com.lekaspos.hw.printer.Images
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Store details and receipt content (synced to every till of the store): name, address,
 * registration numbers, header/footer, receipt language, tax and rounding, e-invoice QR,
 * logo and scale-label formats.
 */
class StoreSettingsActivity : ScreenActivity() {

    private lateinit var name: EditText
    private lateinit var address: EditText
    private lateinit var phone: EditText
    private lateinit var email: EditText
    private lateinit var brn: EditText
    private lateinit var sst: EditText
    private lateinit var tin: EditText
    private lateinit var header: EditText
    private lateinit var footer: EditText
    private lateinit var language: Spinner
    private lateinit var copies: Spinner
    private lateinit var logo: Switch
    private lateinit var removeLogo: Button
    private lateinit var qr: Switch
    private lateinit var qrUrl: EditText
    private lateinit var inclTax: Switch
    private lateinit var rounding: Switch
    private lateinit var templates: EditText
    private lateinit var shiftRequired: Switch
    private lateinit var credit: Switch
    private var form: Form? = null

    /** What was typed before Android ended the app in the background; put back once the form is built. */
    private var typed: Bundle? = null

    /** A picked logo waiting until this screen may run (see [onActivityResult]). */
    private var pendingLogo: Uri? = null
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        typed = savedInstanceState?.getBundle(STATE_FORM)
        setScreen(getString(R.string.settings_store))
        launchUi {
            graph.settings.load()
            build(graph.settings.store.value)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (form?.save() ?: typed)?.let { outState.putBundle(STATE_FORM, it) }
    }

    override fun onStarted(scope: CoroutineScope) {
        entered = true
        pendingLogo?.let { uri ->
            pendingLogo = null
            requireAccess(Perm.SETTINGS) { saveLogo(uri) }
        }
    }

    override fun onStop() {
        entered = false
        super.onStop()
    }

    private fun build(s: StoreSettings) {
        val f = Form(this)
        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val caps = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        f.section(getString(R.string.section_store))
        name = f.text(getString(R.string.store_name), s.name, words)
        address = f.text(getString(R.string.store_address), s.address, words, lines = 3)
        phone = f.text(getString(R.string.store_phone), s.phone, InputType.TYPE_CLASS_PHONE)
        email = f.text(getString(R.string.store_email), s.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        brn = f.text(getString(R.string.store_brn), s.brn, caps)
        sst = f.text(getString(R.string.store_sst), s.sstNo, caps)
        tin = f.text(getString(R.string.store_tin), s.tin, caps)

        f.section(getString(R.string.section_receipt))
        header = f.text(getString(R.string.receipt_header), s.receiptHeader, lines = 2)
        footer = f.text(getString(R.string.receipt_footer), s.receiptFooter, lines = 2)
        language = f.choice(getString(R.string.receipt_language), listOf(getString(R.string.lang_en), getString(R.string.lang_ms)), if (s.receiptLanguage == "ms") 1 else 0)
        copies = f.choice(getString(R.string.receipt_copies), listOf("1", "2", "3"), s.receiptCopies - 1)
        logo = f.switch(getString(R.string.receipt_logo), s.printLogo)
        // The logo is on every receipt: changing it needs the same permission as Save.
        f.button(getString(R.string.receipt_logo_pick)) {
            requireAccess(Perm.SETTINGS) {
                startPicker(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_LOGO)
            }
        }
        removeLogo = f.button(getString(R.string.receipt_logo_remove)) {
            requireAccess(Perm.SETTINGS) {
                launchUi {
                    withContext(Dispatchers.IO) { Images.deleteLogo(applicationContext) }
                    graph.printer.reconnect() // drops the cached printer logo
                    graph.settings.recordChange("receipt logo removed")
                    removeLogo.visible(false)
                    logo.isChecked = false
                }
            }
        }
        removeLogo.visible(false)
        launchUi { removeLogo.visible(withContext(Dispatchers.IO) { Images.hasLogo(applicationContext) }) }
        qr = f.switch(getString(R.string.einvoice_qr), s.einvoiceQr)
        qrUrl = f.text(getString(R.string.einvoice_url), s.einvoiceUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        f.info(getString(R.string.einvoice_help))

        f.section(getString(R.string.section_tax))
        inclTax = f.switch(getString(R.string.prices_incl_tax), s.pricesIncludeTax)
        rounding = f.switch(getString(R.string.cash_rounding), s.currency.cashStep > 1L)

        f.section(getString(R.string.section_cash))
        shiftRequired = f.switch(getString(R.string.shift_required), s.shiftRequired)
        f.info(getString(R.string.shift_required_help))
        credit = f.switch(getString(R.string.credit_enabled), s.creditEnabled)
        f.info(getString(R.string.credit_enabled_help))

        f.section(getString(R.string.section_scale))
        templates = f.text(getString(R.string.scale_templates), s.scaleTemplates.joinToString(", "), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        f.info(getString(R.string.scale_templates_help))

        f.button(getString(R.string.save), primary = true) { save(s) }
        typed?.let { f.restore(it) }
        typed = null
        form = f
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun save(old: StoreSettings) {
        if (!graph.permissions.allowed(Perm.SETTINGS)) {
            requireAccess(Perm.SETTINGS) { save(old) }
            return
        }
        val tpl = templates.text.toString().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        for (t in tpl) {
            try {
                ScaleTemplate(t)
            } catch (e: IllegalArgumentException) {
                templates.error = getString(R.string.scale_invalid, t)
                templates.requestFocus()
                return
            }
        }
        val next = old.copy(
            name = name.text.toString().trim(),
            address = address.text.toString().trim(),
            phone = phone.text.toString().trim(),
            email = email.text.toString().trim(),
            brn = brn.text.toString().trim(),
            sstNo = sst.text.toString().trim(),
            tin = tin.text.toString().trim(),
            receiptHeader = header.text.toString().trim(),
            receiptFooter = footer.text.toString().trim(),
            receiptLanguage = if (language.selectedItemPosition == 1) "ms" else "en",
            receiptCopies = copies.selectedItemPosition + 1,
            printLogo = logo.isChecked,
            einvoiceQr = qr.isChecked,
            einvoiceUrl = qrUrl.text.toString().trim(),
            pricesIncludeTax = inclTax.isChecked,
            currency = old.currency.copy(cashStep = if (rounding.isChecked) CurrencySpec.MYR.cashStep else 0L),
            scaleTemplates = tpl,
            shiftRequired = shiftRequired.isChecked,
            creditEnabled = credit.isChecked,
        )
        launchUi {
            // Only what changed on this screen is written: a field another till changed meanwhile
            // keeps that till's value (2026-10 review).
            graph.settings.saveStore(old, next)
            toast(R.string.saved)
            finish()
        }
    }

    /**
     * A logo picked in the gallery. When Android ended the app meanwhile, the result arrives before
     * the signed-in staff member is known (and the till may be locked): it waits for [onStarted]
     * (2026-10 review).
     */
    @Deprecated("Platform Activity result API (no AndroidX Activity, D-002)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_LOGO || resultCode != RESULT_OK || uri == null) return
        if (entered) requireAccess(Perm.SETTINGS) { saveLogo(uri) } else pendingLogo = uri
    }

    private fun saveLogo(uri: Uri) {
        launchUi {
            withContext(Dispatchers.IO) { Images.saveLogo(applicationContext, uri) }
            graph.printer.reconnect() // drops the cached printer logo
            graph.settings.recordChange("receipt logo")
            if (::logo.isInitialized) {
                logo.isChecked = true
                removeLogo.visible(true)
            }
            toast(R.string.receipt_logo_saved)
        }
    }

    companion object {
        private const val REQ_LOGO = 11
        private const val STATE_FORM = "lekas.store.form"
    }
}
