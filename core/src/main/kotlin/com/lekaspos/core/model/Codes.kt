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

    /** Products imported from a CSV file (detail: how many created and updated). */
    const val PRODUCT_IMPORT = 24
    const val PROMOTION_CHANGE = 25

    /** A customer's credit limit was set or changed (amount: the new limit, 0 = no limit). */
    const val CREDIT_LIMIT_CHANGE = 26

    /** Store settings, the receipt logo or a tax rate changed (detail: what). */
    const val SETTINGS_CHANGE = 27

    /** A customer paid back credit (amount: paid; detail: customer, method, cash rounding). */
    const val CREDIT_PAYMENT = 28

    /** An item taken off the open bill, or its quantity lowered (amount: the value taken off; D-067). */
    const val LINE_REMOVE = 29

    /**
     * [LINE_REMOVE] after the payment screen had shown the bill's total: the customer may have paid the
     * full amount for less on the receipt — the classic till theft (D-067).
     */
    const val LINE_REMOVE_AFTER_PAY = 30

    /** A bill with items cleared after the payment screen had shown its total (amount: the total; D-067). */
    const val BILL_CANCEL_AFTER_PAY = 31

    /** Stock written off or counted down by hand (amount: its value at cost; detail: item, quantity, reason; D-067). */
    const val STOCK_WRITE_OFF = 32

    /** Someone signed in and sold on in another person's open shift without counting the drawer (detail: whose; D-067). */
    const val SHIFT_CONTINUED = 33

    /** Bills cleared ([BILL_CANCEL] and [BILL_CANCEL_AFTER_PAY]). */
    val CLEARED: Set<Int> = setOf(BILL_CANCEL, BILL_CANCEL_AFTER_PAY)

    /** Items taken off bills ([LINE_REMOVE] and [LINE_REMOVE_AFTER_PAY]). */
    val REMOVED: Set<Int> = setOf(LINE_REMOVE, LINE_REMOVE_AFTER_PAY)

    /** Taken off or cleared after the customer saw the total. */
    val AFTER_PAY: Set<Int> = setOf(LINE_REMOVE_AFTER_PAY, BILL_CANCEL_AFTER_PAY)
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

    /**
     * Retired (D-063): clearing a bill or throwing a held bill away needs no permission — taking its
     * lines off one by one never did. Kept so old roles and audit entries still read; not in [ROLE_EDITOR].
     */
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

    /** Sales reports, profit and stock value, report exports. */
    const val REPORTS = 1L shl 17

    /** Add and edit customers, take credit repayments. */
    const val CUSTOMERS = 1L shl 14

    /** Sell on credit ("pay later") within the customer's limit. */
    const val CREDIT_SALE = 1L shl 15

    /** Go over a credit limit, adjust a customer's balance. */
    const val CREDIT_LIMIT = 1L shl 16
    const val ALL = -1L

    /** Every permission bit, in the order the role editor lists them (names of old audit entries too). */
    val LIST: List<Long> = listOf(
        DISCOUNT, PRICE_OVERRIDE, CANCEL_BILL, VOID, REFUND, REPRINT, OPEN_DRAWER, CASH_MOVE, SHIFT_REPORT,
        CUSTOMERS, CREDIT_SALE, CREDIT_LIMIT, MANAGE_PRODUCTS, MANAGE_STOCK, REPORTS, VIEW_AUDIT, SETTINGS, MANAGE_STAFF,
    )

    /** The permissions a role can be given (the role editor's switches): [LIST] without the retired ones. */
    val ROLE_EDITOR: List<Long> = LIST - CANCEL_BILL

    /** Some permission a role can be given is missing from [perms]: a manager may unlock it on the till. */
    fun lacksAny(perms: Long): Boolean = ROLE_EDITOR.any { !has(perms, it) }

    /** [helper] may do something [own] may not (retired bits do not count): a manager who can help on the till. */
    fun addsTo(helper: Long, own: Long): Boolean = ROLE_EDITOR.any { has(helper, it) && !has(own, it) }

    /** Seed roles' starting permissions (the owner edits them in Settings → Staff → Roles). */
    const val DEFAULT_MANAGER: Long = DISCOUNT or PRICE_OVERRIDE or CANCEL_BILL or VOID or REFUND or REPRINT or
        OPEN_DRAWER or CASH_MOVE or SHIFT_REPORT or CUSTOMERS or CREDIT_SALE or CREDIT_LIMIT or MANAGE_PRODUCTS or
        MANAGE_STOCK or VIEW_AUDIT or REPORTS
    const val DEFAULT_CASHIER: Long = REPRINT or CUSTOMERS or CREDIT_SALE

    /**
     * What a manager's help at the till lends the cashier for the bill on it (D-067): discounts, prices,
     * customers and credit within limits. Money out of the drawer (void, refund, drawer, cash in and
     * out), receipt copies, products and the back office still ask for the manager's PIN each time: a
     * PIN typed to help with one bill let the cashier void and refund on other screens in its name.
     */
    const val TILL_HELP: Long = DISCOUNT or PRICE_OVERRIDE or CUSTOMERS or CREDIT_SALE

    /** What a role may do: the owner role everything, other roles their stored bits. */
    fun effective(sysRole: Int, perms: Long): Long = if (sysRole == SysRole.OWNER) ALL else perms

    fun has(perms: Long, perm: Long): Boolean = perms and perm == perm

    /**
     * A role's permissions after an edit made on a screen that showed [shown]: the switches changed
     * there ([shown] → [edited]) over the role as stored now ([current]); the others keep what they
     * are now. Written whole, a screen loaded before the owner took a permission away on another till
     * gave it back (2026-10 review).
     */
    fun merge(shown: Long, edited: Long, current: Long): Long {
        val touched = shown xor edited
        return (current and touched.inv()) or (edited and touched)
    }
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

/** Promotion types (Phase 8, references/money.md §11). */
object PromoKind {
    /** "N for RM X": every group of `buyQty` units costs `groupPrice`. */
    const val MULTI_PRICE = 0

    /** "Buy X get Y free": in every set of `buyQty + freeQty` units, the `freeQty` cheapest are free. */
    const val BUY_GET_FREE = 1
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
    const val PROMOTION = 13

    /** A product's colour and picture on the selling screen (LWW `product_look`, id = product id; v9, D-066). */
    const val PRODUCT_LOOK = 14
    const val SALE = 20
    const val SALE_VOID = 21
    const val STOCK_MOVE = 22
    const val STOCK_COUNT = 23
    const val PURCHASE = 24
    const val CASH_MOVE = 25
    const val CREDIT = 26
    const val AUDIT = 27

    /** A product picture (EVENT `product_image`: a JPEG, never changed; v9, D-066). */
    const val PRODUCT_IMAGE = 28
}

/**
 * Colours of product tiles and categories (`product_look.color`, `category.color`; D-066). 0 = none;
 * the shades themselves are the app's (each keeps white text readable).
 */
object TileColor {
    const val NONE = 0
    const val RED = 1
    const val PINK = 2
    const val PURPLE = 3
    const val INDIGO = 4
    const val BLUE = 5
    const val TEAL = 6
    const val GREEN = 7
    const val LIME = 8
    const val AMBER = 9
    const val ORANGE = 10
    const val BROWN = 11
    const val GREY = 12

    /** Every colour in the order the pickers show them. */
    val ALL: List<Int> = (RED..GREY).toList()

    /** A stored value this version can show (a newer till's colour, or garbage, shows as none). */
    fun known(code: Int): Int = if (code in RED..GREY) code else NONE

    /** The colour a tile shows: its own, else its category's, else none. */
    fun of(product: Int, category: Int): Int = known(product).takeIf { it != NONE } ?: known(category)
}

/** Outbox/sync event operations. */
object EventOp {
    /** Last-writer-wins field update of an LWW entity. */
    const val LWW = 1

    /** Append-only insert of an EVENT entity (with children). */
    const val INSERT = 2
}
