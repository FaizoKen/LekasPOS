package com.lekaspos.domain.sell

import android.database.sqlite.SQLiteDatabase
import com.lekaspos.core.barcode.Gtin
import com.lekaspos.core.barcode.ScaleCode
import com.lekaspos.core.barcode.ScaleTemplate
import com.lekaspos.core.model.BarcodeKind
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.money.Rounding
import com.lekaspos.core.pricing.PricingEngine
import com.lekaspos.data.product.ProductDao
import com.lekaspos.data.product.ScanHit
import com.lekaspos.data.product.SellableProduct

/** What a scanned code means (references/money.md §7). */
sealed class Resolution {
    /** A product barcode or pack barcode. */
    data class Plain(val hit: ScanHit) : Resolution()

    /** An in-store scale label: PLU + weight or price. */
    data class Scale(val product: SellableProduct, val code: String, val label: ScaleCode) : Resolution()

    data class NotFound(val code: String) : Resolution()
}

object BarcodeLookup {

    /**
     * Exact barcode first (UPC-A/EAN-13 variants), then scale-label templates (their prefixes
     * 20–29 are never assigned to manufacturers, so this order is safe). Every template that fits
     * the label is tried in order until its item code is a known PLU (2026-10 review).
     */
    fun resolve(db: SQLiteDatabase, raw: String, templates: List<ScaleTemplate>): Resolution {
        val code = raw.trim()
        if (code.isEmpty()) return Resolution.NotFound(code)
        ProductDao.findByCode(db, Gtin.lookupVariants(code))?.let { return Resolution.Plain(it) }
        for (label in ScaleTemplate.parseAll(templates, code)) {
            val plu = label.itemCode
            val stripped = plu.trimStart('0').ifEmpty { "0" }
            val codes = if (stripped == plu) listOf(plu) else listOf(plu, stripped)
            ProductDao.findByCode(db, codes, BarcodeKind.SCALE_PLU)?.let { return Resolution.Scale(it.product, code, label) }
        }
        // A shop's own label printed from the SKU, or a code a product file put in its SKU column:
        // it was "unknown", and registering it again made a second product (2026-10 review).
        ProductDao.bySku(db, code)?.let { ProductDao.sellableById(db, it) }?.let { p ->
            return Resolution.Plain(ScanHit(p, code, BarcodeKind.BARCODE, 1000L, null))
        }
        return Resolution.NotFound(code)
    }

    /** Price of one selling unit for a barcode: the pack price, or the product price × pack size. */
    fun unitPrice(hit: ScanHit): Long =
        hit.packPrice ?: if (hit.packQty == 1000L) hit.product.price else PricingEngine.lineGross(hit.product.price, hit.packQty)

    /**
     * Quantity for a price-embedded label: the weight the label price stands for (weighed
     * products) or one piece.
     */
    fun labelQty(product: SellableProduct, price: Long): Long {
        if (product.sellMode != SellMode.WEIGHT || product.price <= 0L) return 1000L
        return Rounding.mulDivHalfUp(price, 1000L, product.price).coerceAtLeast(1L)
    }
}
