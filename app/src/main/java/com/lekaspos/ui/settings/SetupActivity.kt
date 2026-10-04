package com.lekaspos.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import com.lekaspos.R
import com.lekaspos.app.AppLanguage
import com.lekaspos.data.db.Seed
import com.lekaspos.data.settings.StoreSettings
import com.lekaspos.ui.common.Form
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.SellActivity
import com.lekaspos.ui.staff.StaffActivity
import kotlinx.coroutines.launch

/**
 * First-run setup (Phase 8): shown once on a fresh install, over the selling screen. Language,
 * the shop's name and contact, then shortcuts to the printer, staff PINs, or joining a shop
 * that already uses LekasPOS on another till (sync brings its products and settings).
 * Everything here can be changed later in Settings.
 */
class SetupActivity : ScreenActivity() {

    private lateinit var name: EditText
    private lateinit var phone: EditText
    private lateinit var address: EditText

    /** The settings the form shows: only what is typed over them is saved. */
    private var shown: StoreSettings? = null
    private var form: Form? = null
    private var typed: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        typed = savedInstanceState?.getBundle(STATE_FORM)
        setScreen(getString(R.string.setup_title))
        launchUi {
            graph.settings.load()
            graph.settings.markSetupDone() // shown once; leaving with Back is fine (all of it is in Settings)
            build()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (form?.save() ?: typed)?.let { outState.putBundle(STATE_FORM, it) }
    }

    private fun build() {
        val s = graph.settings.store.value
        shown = s
        val f = Form(this)
        f.info(getString(R.string.setup_intro))

        val languages = AppLanguage.ALL
        val names = listOf(getString(R.string.language_phone), getString(R.string.lang_en), getString(R.string.lang_ms))
        val current = languages.indexOf(AppLanguage.get(this))
        f.choice(getString(R.string.settings_language), names, current) { i ->
            if (i != current) {
                AppLanguage.set(this, languages[i])
                // The built-in roles, payment methods and owner were named in the phone's language before
                // this choice: renamed while nobody edited them; receipts follow the new language too.
                val g = graph
                g.appScope.launch {
                    g.db().write(reserveIds = 0L) { tx -> Seed.renameUntouched(tx.db, g.seedNames()) }
                    g.settings.languageChanged()
                }
                // The selling screen underneath and this one again, both in the new language.
                val sell = Intent(this, SellActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivities(arrayOf(sell, Intent(this, SetupActivity::class.java)))
            }
        }

        f.section(getString(R.string.setup_shop))
        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        name = f.text(getString(R.string.store_name), s.name, words)
        phone = f.text(getString(R.string.store_phone), s.phone, InputType.TYPE_CLASS_PHONE)
        address = f.text(getString(R.string.store_address), s.address, words, lines = 2)

        f.section(getString(R.string.setup_protect))
        f.info(getString(R.string.setup_protect_help))
        f.button(getString(R.string.safety_setup_drive)) { startActivity(Intent(this, SyncActivity::class.java)) }
        f.button(getString(R.string.safety_setup_folder)) {
            startActivity(Intent(this, BackupActivity::class.java).putExtra(BackupActivity.EXTRA_PICK_FOLDER, true))
        }

        f.section(getString(R.string.setup_optional))
        f.button(getString(R.string.setup_printer)) { startActivity(Intent(this, PrinterSettingsActivity::class.java)) }
        f.button(getString(R.string.setup_staff)) { startActivity(Intent(this, StaffActivity::class.java)) }
        f.info(getString(R.string.setup_join_help))
        f.button(getString(R.string.setup_join)) {
            startActivity(Intent(this, SyncActivity::class.java))
            finish()
        }

        f.button(getString(R.string.setup_start), primary = true) { start() }
        typed?.let { f.restore(it) }
        typed = null
        form = f
        content.removeAllViews()
        content.addView(f.view)
    }

    private fun start() {
        val s = shown ?: return
        val shop = name.text.toString().trim()
        if (shop.isEmpty()) {
            name.error = getString(R.string.setup_name_needed)
            name.requestFocus()
            return
        }
        val contact = s.copy(
            name = shop,
            phone = phone.text.toString().trim(),
            address = address.text.toString().trim(),
        )
        launchUi {
            // Only the shop's name and contact: the defaults of a fresh install written along with
            // them won over the store's real settings once this till joined it (2026-10 review).
            graph.settings.saveStore(s, contact)
            finish()
        }
    }

    private companion object {
        const val STATE_FORM = "lekas.setup.form"
    }
}
