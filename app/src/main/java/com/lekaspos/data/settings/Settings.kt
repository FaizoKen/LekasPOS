package com.lekaspos.data.settings

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.barcode.ScaleTemplate
import com.lekaspos.core.escpos.DrawerPulse
import com.lekaspos.core.escpos.PrinterProfile
import com.lekaspos.core.escpos.TextMode
import com.lekaspos.core.model.Entity
import com.lekaspos.core.model.EventOp
import com.lekaspos.core.money.CurrencySpec
import com.lekaspos.core.receipt.StoreInfo
import com.lekaspos.data.db.Db
import com.lekaspos.data.db.Meta
import com.lekaspos.data.db.args
import com.lekaspos.data.db.queryList
import com.lekaspos.data.db.stringOrNull
import com.lekaspos.data.sync.Outbox

/** Keys of store-wide settings (LWW table `setting`, synced). Never rename a key. */
object SettingKeys {
    const val STORE_NAME = "store.name"
    const val STORE_ADDRESS = "store.address"
    const val STORE_PHONE = "store.phone"
    const val STORE_EMAIL = "store.email"
    const val STORE_BRN = "store.brn"
    const val STORE_SST = "store.sst_no"
    const val STORE_TIN = "store.tin"
    const val RECEIPT_HEADER = "receipt.header"
    const val RECEIPT_FOOTER = "receipt.footer"
    const val RECEIPT_LANG = "receipt.lang"
    const val RECEIPT_LOGO = "receipt.logo"
    const val RECEIPT_COPIES = "receipt.copies"
    const val EINVOICE_QR = "receipt.einvoice_qr"
    const val EINVOICE_URL = "receipt.einvoice_url"
    const val PRICES_INCL_TAX = "tax.prices_include"
    const val CURRENCY_SYMBOL = "currency.symbol"
    const val CURRENCY_DECIMALS = "currency.decimals"
    const val CASH_STEP = "currency.cash_step"
    const val SCALE_TEMPLATES = "scale.templates"
    const val SHIFT_REQUIRED = "shift.required"
    const val CREDIT_ENABLED = "credit.enabled"

    /** Hash of the owner's recovery code (D-037); not part of [StoreSettings]. */
    const val OWNER_RECOVERY = "owner.recovery"
}

/** Store-wide settings with their defaults (D-024: Malaysian defaults). */
data class StoreSettings(
    val name: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val brn: String = "",
    val sstNo: String = "",
    val tin: String = "",
    val receiptHeader: String = "",
    val receiptFooter: String = "",
    /** "en" or "ms". */
    val receiptLanguage: String = "en",
    val printLogo: Boolean = false,
    val receiptCopies: Int = 1,
    val einvoiceQr: Boolean = false,
    /** QR text; `{receipt}`, `{total}`, `{date}` are filled in per sale. */
    val einvoiceUrl: String = "",
    val pricesIncludeTax: Boolean = true,
    val currency: CurrencySpec = CurrencySpec.MYR,
    val scaleTemplates: List<String> = DEFAULT_SCALE_TEMPLATES,
    /** Payments need an open shift on this till (D-038). */
    val shiftRequired: Boolean = false,
    /** Customers and "pay later" credit (D-039). */
    val creditEnabled: Boolean = false,
) {
    val cashStep: Long get() = currency.cashStep

    fun storeInfo(): StoreInfo = StoreInfo(
        name = name, address = address, phone = phone, brn = brn, sstNo = sstNo, tin = tin, email = email,
        header = receiptHeader, footer = receiptFooter,
    )

    fun templates(): List<ScaleTemplate> = scaleTemplates.mapNotNull {
        try {
            ScaleTemplate(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Values as stored in the `setting` table. */
    fun toMap(): Map<String, String> = linkedMapOf(
        SettingKeys.STORE_NAME to name,
        SettingKeys.STORE_ADDRESS to address,
        SettingKeys.STORE_PHONE to phone,
        SettingKeys.STORE_EMAIL to email,
        SettingKeys.STORE_BRN to brn,
        SettingKeys.STORE_SST to sstNo,
        SettingKeys.STORE_TIN to tin,
        SettingKeys.RECEIPT_HEADER to receiptHeader,
        SettingKeys.RECEIPT_FOOTER to receiptFooter,
        SettingKeys.RECEIPT_LANG to receiptLanguage,
        SettingKeys.RECEIPT_LOGO to flag(printLogo),
        SettingKeys.RECEIPT_COPIES to receiptCopies.toString(),
        SettingKeys.EINVOICE_QR to flag(einvoiceQr),
        SettingKeys.EINVOICE_URL to einvoiceUrl,
        SettingKeys.PRICES_INCL_TAX to flag(pricesIncludeTax),
        SettingKeys.CURRENCY_SYMBOL to currency.symbol,
        SettingKeys.CURRENCY_DECIMALS to currency.decimals.toString(),
        SettingKeys.CASH_STEP to currency.cashStep.toString(),
        SettingKeys.SCALE_TEMPLATES to scaleTemplates.joinToString(","),
        SettingKeys.SHIFT_REQUIRED to flag(shiftRequired),
        SettingKeys.CREDIT_ENABLED to flag(creditEnabled),
    )

    companion object {
        val DEFAULT_SCALE_TEMPLATES = listOf("20IIIIIWWWWWC", "21IIIIIPPPPPC")

        fun from(m: Map<String, String?>, defaultLanguage: String): StoreSettings {
            val d = StoreSettings()
            fun s(key: String, def: String) = m[key] ?: def
            fun b(key: String, def: Boolean) = m[key]?.let { it == "1" } ?: def
            val decimals = m[SettingKeys.CURRENCY_DECIMALS]?.toIntOrNull()?.takeIf { it in 0..3 } ?: 2
            val step = m[SettingKeys.CASH_STEP]?.toLongOrNull()?.takeIf { it >= 0L } ?: CurrencySpec.MYR.cashStep
            return StoreSettings(
                name = s(SettingKeys.STORE_NAME, d.name),
                address = s(SettingKeys.STORE_ADDRESS, d.address),
                phone = s(SettingKeys.STORE_PHONE, d.phone),
                email = s(SettingKeys.STORE_EMAIL, d.email),
                brn = s(SettingKeys.STORE_BRN, d.brn),
                sstNo = s(SettingKeys.STORE_SST, d.sstNo),
                tin = s(SettingKeys.STORE_TIN, d.tin),
                receiptHeader = s(SettingKeys.RECEIPT_HEADER, d.receiptHeader),
                receiptFooter = s(SettingKeys.RECEIPT_FOOTER, d.receiptFooter),
                receiptLanguage = m[SettingKeys.RECEIPT_LANG]?.takeIf { it == "en" || it == "ms" } ?: defaultLanguage,
                printLogo = b(SettingKeys.RECEIPT_LOGO, d.printLogo),
                receiptCopies = m[SettingKeys.RECEIPT_COPIES]?.toIntOrNull()?.coerceIn(1, 3) ?: 1,
                einvoiceQr = b(SettingKeys.EINVOICE_QR, d.einvoiceQr),
                einvoiceUrl = s(SettingKeys.EINVOICE_URL, d.einvoiceUrl),
                pricesIncludeTax = b(SettingKeys.PRICES_INCL_TAX, d.pricesIncludeTax),
                currency = CurrencySpec.MYR.copy(
                    symbol = m[SettingKeys.CURRENCY_SYMBOL] ?: CurrencySpec.MYR.symbol,
                    decimals = decimals,
                    cashStep = step,
                ),
                scaleTemplates = m[SettingKeys.SCALE_TEMPLATES]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: DEFAULT_SCALE_TEMPLATES,
                shiftRequired = b(SettingKeys.SHIFT_REQUIRED, d.shiftRequired),
                creditEnabled = b(SettingKeys.CREDIT_ENABLED, d.creditEnabled),
            )
        }

        private fun flag(b: Boolean) = if (b) "1" else "0"
    }
}

/** Store settings in the LWW `setting` table: one register per key (references/sync.md §4). */
object SettingsDao {
    private const val UPDATE = "UPDATE setting SET value = ?, updated_at = ?, ver_hlc = ?, ver_dev = ? WHERE key = ?"
    private const val INSERT = "INSERT INTO setting(key, value, updated_at, ver_hlc, ver_dev) VALUES(?, ?, ?, ?, ?)"

    fun all(db: SQLiteDatabase): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        db.queryList("SELECT key, value FROM setting") { it.getString(0) to it.stringOrNull(1) }.forEach { out[it.first] = it.second }
        return out
    }

    fun put(tx: Db.Tx, key: String, value: String?, now: Long) {
        val hlc = tx.hlcNow()
        tx.updateOrInsert(UPDATE, arrayOf<Any?>(value, now, hlc, tx.deviceNo, key), INSERT, arrayOf<Any?>(key, value, now, hlc, tx.deviceNo))
        if (tx.syncEnabled) {
            val payload = Outbox.json { w ->
                w.beginObject()
                w.name("key").value(key)
                w.name("value").value(value)
                w.name("hlc").value(hlc)
                w.name("dev").value(tx.deviceNo.toLong())
                w.endObject()
            }
            Outbox.append(tx, Entity.SETTING, EventOp.LWW, null, hlc, payload)
        }
    }

    /** Writes only the keys whose value differs from [current]. Returns the number written. */
    fun putChanged(tx: Db.Tx, current: Map<String, String?>, wanted: Map<String, String>, now: Long): Int {
        var n = 0
        for ((k, v) in wanted) {
            if (current[k] != v) {
                put(tx, k, v, now)
                n++
            }
        }
        return n
    }
}

/**
 * Settings of this device only (printer, scanner, camera), kept in the LOCAL `meta` table:
 * never synced, because each till has its own hardware.
 */
data class DeviceSettings(
    val printerAddress: String? = null,
    val printerName: String? = null,
    /** 58 or 80 (mm); 81 = 80 mm printers with 42 columns / 512 dots. */
    val paper: Int = 58,
    /** 0 auto (text when every character can be printed as text, else image), 1 text, 2 image. */
    val printMode: Int = MODE_AUTO,
    /** The printer has Chinese fonts (GB18030 text mode). */
    val chinesePrinter: Boolean = false,
    val codePage: Int = 0,
    val cut: Boolean = true,
    val feedLines: Int = 4,
    val autoPrint: Boolean = true,
    val nativeQr: Boolean = false,
    val drawerEnabled: Boolean = true,
    val drawerPin: Int = 0,
    val scannerAddress: String? = null,
    val scannerName: String? = null,
    val cameraScan: Boolean = true,
    /** Lock the till after this many idle minutes (0 = never) while PIN login is on. */
    val autoLockMinutes: Int = 0,
) {
    val cols: Int get() = when (paper) { 80 -> 48; 81 -> 42; else -> 32 }
    val dots: Int get() = when (paper) { 80 -> 576; 81 -> 512; else -> 384 }
    val hasPrinter: Boolean get() = !printerAddress.isNullOrEmpty()

    fun profile(): PrinterProfile = PrinterProfile(
        cols = cols,
        dots = dots,
        textMode = if (chinesePrinter) TextMode.GB18030 else TextMode.LATIN,
        codePage = codePage,
        cut = cut,
        feedLines = feedLines,
        nativeQr = nativeQr,
    )

    fun drawer(): DrawerPulse = DrawerPulse(pin = drawerPin)

    companion object {
        const val MODE_AUTO = 0
        const val MODE_TEXT = 1
        const val MODE_IMAGE = 2

        private const val P = "dev."
        private const val PRINTER_ADDRESS = "dev.printer.address"
        private const val PRINTER_NAME = "dev.printer.name"
        private const val PAPER = "dev.printer.paper"
        private const val MODE = "dev.printer.mode"
        private const val CHINESE = "dev.printer.chinese"
        private const val CODE_PAGE = "dev.printer.codepage"
        private const val CUT = "dev.printer.cut"
        private const val FEED = "dev.printer.feed"
        private const val AUTO_PRINT = "dev.printer.auto"
        private const val NATIVE_QR = "dev.printer.native_qr"
        private const val DRAWER = "dev.drawer.enabled"
        private const val DRAWER_PIN = "dev.drawer.pin"
        private const val SCANNER_ADDRESS = "dev.scanner.address"
        private const val SCANNER_NAME = "dev.scanner.name"
        private const val CAMERA = "dev.camera.enabled"
        private const val AUTO_LOCK = "dev.lock.minutes"

        fun load(db: SQLiteDatabase): DeviceSettings {
            val m = HashMap<String, String?>()
            // `dev.` … `dev/` is a primary-key range ('/' follows '.').
            db.queryList("SELECT key, value FROM meta WHERE key >= ? AND key < ?", args(P, "dev/")) {
                it.getString(0) to it.stringOrNull(1)
            }.forEach { m[it.first] = it.second }
            val d = DeviceSettings()
            fun b(k: String, def: Boolean) = m[k]?.let { it == "1" } ?: def
            fun i(k: String, def: Int) = m[k]?.toIntOrNull() ?: def
            return DeviceSettings(
                printerAddress = m[PRINTER_ADDRESS]?.takeIf { it.isNotEmpty() },
                printerName = m[PRINTER_NAME],
                paper = i(PAPER, d.paper).takeIf { it == 58 || it == 80 || it == 81 } ?: 58,
                printMode = i(MODE, d.printMode).coerceIn(0, 2),
                chinesePrinter = b(CHINESE, d.chinesePrinter),
                codePage = i(CODE_PAGE, d.codePage).coerceIn(0, 255),
                cut = b(CUT, d.cut),
                feedLines = i(FEED, d.feedLines).coerceIn(0, 10),
                autoPrint = b(AUTO_PRINT, d.autoPrint),
                nativeQr = b(NATIVE_QR, d.nativeQr),
                drawerEnabled = b(DRAWER, d.drawerEnabled),
                drawerPin = i(DRAWER_PIN, d.drawerPin).coerceIn(0, 1),
                scannerAddress = m[SCANNER_ADDRESS]?.takeIf { it.isNotEmpty() },
                scannerName = m[SCANNER_NAME],
                cameraScan = b(CAMERA, d.cameraScan),
                autoLockMinutes = i(AUTO_LOCK, d.autoLockMinutes).coerceIn(0, 240),
            )
        }

        fun save(tx: Db.Tx, s: DeviceSettings) {
            fun put(k: String, v: String?) = Meta.put(tx.db, k, v)
            fun flag(v: Boolean) = if (v) "1" else "0"
            put(PRINTER_ADDRESS, s.printerAddress ?: "")
            put(PRINTER_NAME, s.printerName)
            put(PAPER, s.paper.toString())
            put(MODE, s.printMode.toString())
            put(CHINESE, flag(s.chinesePrinter))
            put(CODE_PAGE, s.codePage.toString())
            put(CUT, flag(s.cut))
            put(FEED, s.feedLines.toString())
            put(AUTO_PRINT, flag(s.autoPrint))
            put(NATIVE_QR, flag(s.nativeQr))
            put(DRAWER, flag(s.drawerEnabled))
            put(DRAWER_PIN, s.drawerPin.toString())
            put(SCANNER_ADDRESS, s.scannerAddress ?: "")
            put(SCANNER_NAME, s.scannerName)
            put(CAMERA, flag(s.cameraScan))
            put(AUTO_LOCK, s.autoLockMinutes.toString())
        }
    }
}
