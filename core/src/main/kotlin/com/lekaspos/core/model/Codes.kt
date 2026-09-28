package com.lekaspos.core.model

/*
 * Stable integer codes stored in the database and in sync files.
 * NEVER renumber or reuse a value — only append new ones.
 */

object SaleKind {
    const val SALE = 0
    const val REFUND = 1
}

object SaleStatus {
    const val COMPLETED = 0
    const val VOIDED = 1
}

object PaymentKind {
    const val CASH = 1
    const val CARD = 2
    const val EWALLET = 3
    const val CREDIT = 4
    const val OTHER = 9
}

object SellMode {
    const val UNIT = 0
    const val WEIGHT = 1
    const val OPEN_PRICE = 2
}

object BarcodeKind {
    const val BARCODE = 0
    const val SCALE_PLU = 1
}

object MovementKind {
    const val RECEIVE = 1
    const val ADJUST = 2
    const val WASTE = 3
    const val RETURN_TO_SUPPLIER = 4
    const val TRANSFER_IN = 5
    const val TRANSFER_OUT = 6
    const val OPENING = 7
}

object CashMoveKind {
    const val CASH_IN = 1
    const val CASH_OUT = 2
    const val DROP = 3
}

object CreditKind {
    const val CHARGE = 1
    const val PAYMENT = 2
    const val ADJUST = 3
}

object DiscountKind {
    const val NONE = 0
    const val AMOUNT = 1
    const val PERCENT = 2
}

object CartStatus {
    const val OPEN = 0
    const val HELD = 1
}

object PrintJobStatus {
    const val PENDING = 0
    const val DONE = 1
    const val FAILED = 2
}

object SysRole {
    const val NONE = 0
    const val OWNER = 1
    const val MANAGER = 2
    const val CASHIER = 3
}

/** Entity type codes used by the outbox, sync files and the audit log. */
object Entity {
    const val SETTING = 1
    const val ROLE = 2
    const val STAFF = 3
    const val TAX_RATE = 4
    const val CATEGORY = 5
    const val PRODUCT = 6
    const val BARCODE = 7
    const val SUPPLIER = 8
    const val CUSTOMER = 9
    const val PAYMENT_METHOD = 10
    const val SHIFT = 11
    const val SALE = 20
    const val SALE_VOID = 21
    const val STOCK_MOVE = 22
    const val STOCK_COUNT = 23
    const val PURCHASE = 24
    const val CASH_MOVE = 25
    const val CREDIT = 26
    const val AUDIT = 27
}

/** Outbox/sync event operations. */
object EventOp {
    /** Last-writer-wins field update of an LWW entity. */
    const val LWW = 1

    /** Append-only insert of an EVENT entity (with children). */
    const val INSERT = 2
}
