package com.lekaspos.core.receipt

/** Store details printed at the top of every receipt (from store settings). */
data class StoreInfo(
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    /** Business registration number (SSM). */
    val brn: String? = null,
    val sstNo: String? = null,
    val tin: String? = null,
    val email: String? = null,
    val header: String? = null,
    val footer: String? = null,
)

data class ReceiptItem(
    val name: String,
    /** Milli-units of the selling unit (negative on refunds). */
    val qty: Long,
    val unit: String? = null,
    val weighed: Boolean = false,
    val unitPrice: Long,
    val gross: Long,
    /** Line discount (positive = money off; negative on refunds). */
    val discount: Long = 0L,
    /** Name of the promotion behind [discount] (printed instead of "Discount"). */
    val promo: String? = null,
)

data class ReceiptTax(val name: String, val bp: Int, val amount: Long)

data class ReceiptPayment(val name: String, val amount: Long, val tendered: Long = 0L, val change: Long = 0L)

/** Everything a receipt shows; built from the stored sale, never from the live cart. */
data class ReceiptDoc(
    val store: StoreInfo,
    val refund: Boolean = false,
    val receiptNo: String,
    val refReceiptNo: String? = null,
    val soldAt: Long,
    val cashier: String? = null,
    val customer: String? = null,
    val items: List<ReceiptItem>,
    val subtotal: Long,
    val lineDiscounts: Long = 0L,
    val billDiscount: Long = 0L,
    val tax: Long = 0L,
    val taxes: List<ReceiptTax> = emptyList(),
    val pricesIncludeTax: Boolean = true,
    val rounding: Long = 0L,
    val total: Long,
    val payments: List<ReceiptPayment> = emptyList(),
    val change: Long = 0L,
    val note: String? = null,
    val qrData: String? = null,
    val voided: Boolean = false,
    val reprint: Boolean = false,
)

/** Receipt labels in the receipt language (independent of the device language). */
data class ReceiptText(
    val receipt: String,
    val refund: String,
    val receiptNo: String,
    val date: String,
    val cashier: String,
    val customer: String,
    val original: String,
    val subtotal: String,
    val itemDiscounts: String,
    val billDiscount: String,
    val discount: String,
    val taxIncluded: String,
    val rounding: String,
    val total: String,
    val change: String,
    val items: String,
    val reprint: String,
    val voided: String,
    val thankYou: String,
    val phone: String,
    val brn: String,
    val sst: String,
    val tin: String,
    val einvoice: String,
    val note: String,
    /**
     * Names for a tax rate or payment method whose row has not arrived from the till that made it (sync
     * brings a sale first): in the receipt's language, not English on a Malay receipt (2026-10 review).
     */
    val tax: String = "Tax",
    val cash: String = "Cash",
    val card: String = "Card",
    val ewallet: String = "E-wallet",
    val credit: String = "Credit",
    val other: String = "Other",
) {
    companion object {
        val EN = ReceiptText(
            receipt = "RECEIPT",
            refund = "REFUND",
            receiptNo = "No",
            date = "Date",
            cashier = "Cashier",
            customer = "Customer",
            original = "Original",
            subtotal = "Subtotal",
            itemDiscounts = "Item discounts",
            billDiscount = "Bill discount",
            discount = "Discount",
            taxIncluded = "Incl.",
            rounding = "Rounding",
            total = "TOTAL",
            change = "Change",
            items = "Items",
            reprint = "COPY",
            voided = "VOIDED",
            thankYou = "Thank you!",
            phone = "Tel",
            brn = "Reg. No",
            sst = "SST No",
            tin = "TIN",
            einvoice = "Scan to request an e-invoice",
            note = "Note",
        )
        val MS = ReceiptText(
            receipt = "RESIT",
            refund = "BAYARAN BALIK",
            receiptNo = "No",
            date = "Tarikh",
            cashier = "Juruwang",
            customer = "Pelanggan",
            original = "Resit asal",
            subtotal = "Jumlah kecil",
            itemDiscounts = "Diskaun item",
            billDiscount = "Diskaun bil",
            discount = "Diskaun",
            taxIncluded = "Termasuk",
            rounding = "Pembundaran",
            total = "JUMLAH",
            change = "Baki",
            items = "Item",
            reprint = "SALINAN",
            voided = "DIBATALKAN",
            thankYou = "Terima kasih!",
            phone = "Tel",
            brn = "No. Daftar",
            sst = "No. SST",
            tin = "TIN",
            einvoice = "Imbas untuk minta e-invois",
            note = "Nota",
            tax = "Cukai",
            cash = "Tunai",
            card = "Kad",
            ewallet = "E-dompet",
            credit = "Kredit",
            other = "Lain-lain",
        )

        fun forLanguage(code: String): ReceiptText = if (code == "ms") MS else EN
    }
}
