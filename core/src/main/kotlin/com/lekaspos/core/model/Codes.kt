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

/** Actions written to `audit_log.action`. */
object AuditAction {
    const val SALE_VOID = 1
    const val REFUND = 2
    const val PRICE_OVERRIDE = 3
    const val LINE_DISCOUNT = 4
    const val BILL_DISCOUNT = 5
    const val DRAWER_OPEN = 6
    const val REPRINT = 7
    const val PRODUCT_PRICE_CHANGE = 8
    const val PRODUCT_DELETE = 9
    const val BILL_CANCEL = 10

    /** A manager approved access to a screen for another staff member (detail: permission). */
    const val APPROVAL = 11
    const val SIGN_IN = 12

    /** Too many wrong PINs: this till waits before the next try. */
    const val PIN_LOCKOUT = 13
    const val STAFF_CHANGE = 14
    const val ROLE_CHANGE = 15
    const val SHIFT_OPEN = 16

    /** amount = counted − expected cash. */
    const val SHIFT_CLOSE = 17
    const val CASH_IN = 18
    const val CASH_OUT = 19
    const val CASH_DROP = 20
    const val CREDIT_ADJUST = 21

    /** A credit sale over the customer's limit (approved). */
    const val CREDIT_OVER_LIMIT = 22

    /** The owner PIN was reset with the recovery code. */
    const val OWNER_PIN_RESET = 23
}

/** Permission bits stored in `role.perms`. The owner role always has every permission. */
object Perm {
    const val VOID = 1L shl 0
    const val REFUND = 1L shl 1
    const val PRICE_OVERRIDE = 1L shl 2
    const val DISCOUNT = 1L shl 3
    const val OPEN_DRAWER = 1L shl 4
    const val REPRINT = 1L shl 5
    const val MANAGE_PRODUCTS = 1L shl 6
    const val SETTINGS = 1L shl 7
    const val CANCEL_BILL = 1L shl 8

    /** Receive stock, adjust stock, count stock, edit suppliers. */
    const val MANAGE_STOCK = 1L shl 9

    /** Add and edit staff, set their PINs, edit roles. */
    const val MANAGE_STAFF = 1L shl 10

    /** Cash in, cash out and cash drops during a shift. */
    const val CASH_MOVE = 1L shl 11

    /** See expected cash and shift reports (without it, closing a shift is a blind count). */
    const val SHIFT_REPORT = 1L shl 12
    const val VIEW_AUDIT = 1L shl 13

    /** Add and edit customers, take credit repayments. */
    const val CUSTOMERS = 1L shl 14

    /** Sell on credit ("pay later") within the customer's limit. */
    const val CREDIT_SALE = 1L shl 15

    /** Go over a credit limit, adjust a customer's balance. */
    const val CREDIT_LIMIT = 1L shl 16
    const val ALL = -1L

    /** Every assignable permission, in the order the role editor lists them. */
    val LIST: List<Long> = listOf(
        DISCOUNT, PRICE_OVERRIDE, CANCEL_BILL, VOID, REFUND, REPRINT, OPEN_DRAWER, CASH_MOVE, SHIFT_REPORT,
        CUSTOMERS, CREDIT_SALE, CREDIT_LIMIT, MANAGE_PRODUCTS, MANAGE_STOCK, VIEW_AUDIT, SETTINGS, MANAGE_STAFF,
    )

    /** Seed roles' starting permissions (the owner edits them in Settings → Staff → Roles). */
    const val DEFAULT_MANAGER: Long = DISCOUNT or PRICE_OVERRIDE or CANCEL_BILL or VOID or REFUND or REPRINT or
        OPEN_DRAWER or CASH_MOVE or SHIFT_REPORT or CUSTOMERS or CREDIT_SALE or CREDIT_LIMIT or MANAGE_PRODUCTS or
        MANAGE_STOCK or VIEW_AUDIT
    const val DEFAULT_CASHIER: Long = REPRINT or CUSTOMERS or CREDIT_SALE

    /** What a role may do: the owner role everything, other roles their stored bits. */
    fun effective(sysRole: Int, perms: Long): Long = if (sysRole == SysRole.OWNER) ALL else perms

    fun has(perms: Long, perm: Long): Boolean = perms and perm == perm
}

/** `count_session.status`. */
object CountSessionStatus {
    const val OPEN = 0
    const val FINISHED = 1
}

/** `print_job.kind`. */
object PrintJobKind {
    const val RECEIPT = 1
    const val REPRINT = 2
    const val TEST = 3
    const val DRAWER = 4

    /** A shift report; `ref_id` = shift id. */
    const val SHIFT = 5
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
    const val COUNT_SESSION = 12
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
