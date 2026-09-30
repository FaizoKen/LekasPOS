package com.lekaspos.data.db

/**
 * Authoritative DDL of the current schema version (explained in the skill's
 * references/database.md). Every statement must run on SQLite 3.8.4 (Android 5.0).
 *
 * Changing anything here requires: bump [VERSION], add a Migration, and add the snapshot
 * `app/src/androidTest/assets/schemas/<VERSION>.sql` (SchemaSnapshotTest prints it).
 */
object Schema {
    const val VERSION = 6
    const val FILE_NAME = "lekaspos.db"

    /** LWW columns shared by all editable master-data tables. */
    private const val LWW = """
        deleted INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        ver_hlc INTEGER NOT NULL,
        ver_dev INTEGER NOT NULL,
        fver TEXT"""

    /** Promotions (v6, Phase 8): LWW; `products` = the product ids, comma-separated. */
    const val PROMOTION = """CREATE TABLE promotion (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            kind INTEGER NOT NULL,
            buy_qty INTEGER NOT NULL,
            free_qty INTEGER NOT NULL DEFAULT 0,
            group_price INTEGER NOT NULL DEFAULT 0,
            products TEXT NOT NULL DEFAULT '',
            start_day INTEGER,
            end_day INTEGER,
            active INTEGER NOT NULL DEFAULT 1,
        )"""

    /** Sync events of a kind this version does not know yet, kept until an update knows it (v6, D-047). */
    const val SYNC_DEFERRED = """CREATE TABLE sync_deferred (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            entity INTEGER NOT NULL,
            op INTEGER NOT NULL,
            row_id INTEGER,
            hlc INTEGER NOT NULL,
            payload TEXT NOT NULL
        )"""

    val STATEMENTS: List<String> = listOf(
        // ---------- LOCAL: device identity, sequences, flags ----------
        """CREATE TABLE meta (
            key TEXT NOT NULL PRIMARY KEY,
            value TEXT
        ) WITHOUT ROWID""",

        // ---------- LWW: store settings (one register per key) ----------
        """CREATE TABLE setting (
            key TEXT NOT NULL PRIMARY KEY,
            value TEXT,
            updated_at INTEGER NOT NULL,
            ver_hlc INTEGER NOT NULL,
            ver_dev INTEGER NOT NULL
        ) WITHOUT ROWID""",

        // ---------- LWW: master data ----------
        """CREATE TABLE role (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            sys_role INTEGER NOT NULL DEFAULT 0,
            perms INTEGER NOT NULL DEFAULT 0,$LWW
        )""",
        """CREATE TABLE staff (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            role_id INTEGER NOT NULL,
            pin_hash TEXT,
            pin_salt TEXT,
            active INTEGER NOT NULL DEFAULT 1,$LWW
        )""",
        """CREATE TABLE tax_rate (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            code TEXT,
            rate_bp INTEGER NOT NULL,$LWW
        )""",
        """CREATE TABLE category (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            name_key TEXT NOT NULL,
            color INTEGER NOT NULL DEFAULT 0,
            sort INTEGER NOT NULL DEFAULT 0,$LWW
        )""",
        "CREATE INDEX category_sort ON category(sort, name_key) WHERE deleted = 0",
        """CREATE TABLE product (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            name_key TEXT NOT NULL,
            sku TEXT,
            category_id INTEGER,
            unit TEXT NOT NULL DEFAULT 'pcs',
            sell_mode INTEGER NOT NULL DEFAULT 0,
            price INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            tax_rate_id INTEGER,
            track_stock INTEGER NOT NULL DEFAULT 1,
            low_stock INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 1,$LWW
        )""",
        // Partial-index predicates are only `deleted = 0` or a lone `col IS NOT NULL`: SQLite 3.8
        // ignores predicates that mix IS NOT NULL with other terms (references/database.md §2).
        "CREATE INDEX product_name ON product(name_key) WHERE deleted = 0",
        "CREATE INDEX product_category ON product(category_id, name_key) WHERE deleted = 0",
        "CREATE INDEX product_sku ON product(sku) WHERE deleted = 0",
        """CREATE TABLE product_barcode (
            id INTEGER PRIMARY KEY,
            product_id INTEGER NOT NULL,
            code TEXT NOT NULL,
            kind INTEGER NOT NULL DEFAULT 0,
            pack_qty INTEGER NOT NULL DEFAULT 1000,
            pack_price INTEGER,$LWW
        )""",
        "CREATE INDEX product_barcode_code ON product_barcode(code) WHERE deleted = 0",
        "CREATE INDEX product_barcode_product ON product_barcode(product_id) WHERE deleted = 0",
        // DERIVED: search index, docid = product.id, text normalized by :core SearchText.
        """CREATE VIRTUAL TABLE product_fts USING fts4(body, tokenize=simple, prefix="2,3")""",
        """CREATE TABLE supplier (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            name_key TEXT NOT NULL,
            contact TEXT,
            phone TEXT,
            email TEXT,
            address TEXT,
            note TEXT,$LWW
        )""",
        "CREATE INDEX supplier_name ON supplier(name_key) WHERE deleted = 0",
        PROMOTION,
        """CREATE TABLE customer (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            name_key TEXT NOT NULL,
            phone TEXT,
            email TEXT,
            address TEXT,
            tin TEXT,
            note TEXT,
            credit_limit INTEGER NOT NULL DEFAULT 0,$LWW
        )""",
        "CREATE INDEX customer_name ON customer(name_key) WHERE deleted = 0",
        "CREATE INDEX customer_phone ON customer(phone) WHERE deleted = 0",
        """CREATE TABLE payment_method (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            kind INTEGER NOT NULL,
            opens_drawer INTEGER NOT NULL DEFAULT 0,
            sort INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 1,$LWW
        )""",
        // Only the device that opened a shift edits it, so LWW never has real conflicts here.
        """CREATE TABLE shift (
            id INTEGER PRIMARY KEY,
            device_no INTEGER NOT NULL,
            opened_by INTEGER NOT NULL,
            opened_at INTEGER NOT NULL,
            opening_float INTEGER NOT NULL DEFAULT 0,
            closed_by INTEGER,
            closed_at INTEGER,
            counted_cash INTEGER,
            expected_cash INTEGER,
            note TEXT,$LWW
        )""",
        "CREATE INDEX shift_opened ON shift(opened_at)",
        // v2: a stock count (stock take). Counts apply as they are entered; the session groups them.
        """CREATE TABLE count_session (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            status INTEGER NOT NULL DEFAULT 0,
            category_id INTEGER,
            started_at INTEGER NOT NULL,
            finished_at INTEGER,
            staff_id INTEGER,
            note TEXT,$LWW
        )""",
        "CREATE INDEX count_session_started ON count_session(started_at) WHERE deleted = 0",

        // ---------- EVENT: append-only facts ----------
        """CREATE TABLE cash_movement (
            id INTEGER PRIMARY KEY,
            shift_id INTEGER NOT NULL,
            kind INTEGER NOT NULL,
            amount INTEGER NOT NULL,
            reason TEXT,
            staff_id INTEGER,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL
        )""",
        "CREATE INDEX cash_movement_shift ON cash_movement(shift_id)",
        """CREATE TABLE sale (
            id INTEGER PRIMARY KEY,
            kind INTEGER NOT NULL DEFAULT 0,
            receipt_no TEXT NOT NULL,
            device_no INTEGER NOT NULL,
            doc_seq INTEGER NOT NULL,
            ref_sale_id INTEGER,
            shift_id INTEGER,
            staff_id INTEGER,
            customer_id INTEGER,
            opened_at INTEGER NOT NULL,
            sold_at INTEGER NOT NULL,
            day INTEGER NOT NULL,
            line_count INTEGER NOT NULL,
            subtotal INTEGER NOT NULL,
            discount INTEGER NOT NULL DEFAULT 0,
            tax INTEGER NOT NULL DEFAULT 0,
            rounding INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL,
            paid INTEGER NOT NULL,
            change_due INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            prices_incl_tax INTEGER NOT NULL DEFAULT 1,
            status INTEGER NOT NULL DEFAULT 0,
            refunded INTEGER NOT NULL DEFAULT 0,
            note TEXT,
            hlc INTEGER NOT NULL
        )""",
        "CREATE INDEX sale_sold ON sale(sold_at)",
        "CREATE INDEX sale_receipt ON sale(receipt_no)",
        "CREATE INDEX sale_shift ON sale(shift_id)",
        "CREATE INDEX sale_customer ON sale(customer_id) WHERE customer_id IS NOT NULL",
        "CREATE INDEX sale_ref ON sale(ref_sale_id) WHERE ref_sale_id IS NOT NULL",
        // Sale lines are also the stock movements of sales (stock_qty, hlc) — D-008.
        """CREATE TABLE sale_line (
            id INTEGER PRIMARY KEY,
            sale_id INTEGER NOT NULL REFERENCES sale(id) ON DELETE CASCADE,
            line_no INTEGER NOT NULL,
            product_id INTEGER,
            ref_line_id INTEGER,
            name TEXT NOT NULL,
            barcode TEXT,
            unit TEXT,
            category_id INTEGER,
            qty INTEGER NOT NULL,
            base_qty INTEGER NOT NULL,
            unit_price INTEGER NOT NULL,
            gross INTEGER NOT NULL,
            discount INTEGER NOT NULL DEFAULT 0,
            bill_discount INTEGER NOT NULL DEFAULT 0,
            net INTEGER NOT NULL,
            tax_rate_id INTEGER,
            tax_bp INTEGER NOT NULL DEFAULT 0,
            tax INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            price_overridden INTEGER NOT NULL DEFAULT 0,
            stock_qty INTEGER NOT NULL DEFAULT 0,
            hlc INTEGER NOT NULL,
            promo_id INTEGER,
            promo_name TEXT
        )""",
        "CREATE INDEX sale_line_sale ON sale_line(sale_id)",
        "CREATE INDEX sale_line_product ON sale_line(product_id, hlc)",
        """CREATE TABLE payment (
            id INTEGER PRIMARY KEY,
            sale_id INTEGER NOT NULL REFERENCES sale(id) ON DELETE CASCADE,
            method_id INTEGER NOT NULL,
            kind INTEGER NOT NULL,
            amount INTEGER NOT NULL,
            tendered INTEGER NOT NULL DEFAULT 0,
            change_given INTEGER NOT NULL DEFAULT 0,
            ref TEXT,
            shift_id INTEGER,
            paid_at INTEGER NOT NULL
        )""",
        "CREATE INDEX payment_sale ON payment(sale_id)",
        "CREATE INDEX payment_shift ON payment(shift_id, kind) WHERE shift_id IS NOT NULL",
        """CREATE TABLE sale_void (
            id INTEGER PRIMARY KEY,
            sale_id INTEGER NOT NULL,
            reason TEXT NOT NULL,
            staff_id INTEGER,
            approved_by INTEGER,
            shift_id INTEGER,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL
        )""",
        "CREATE INDEX sale_void_sale ON sale_void(sale_id)",
        "CREATE INDEX sale_void_shift ON sale_void(shift_id) WHERE shift_id IS NOT NULL",
        """CREATE TABLE stock_movement (
            id INTEGER PRIMARY KEY,
            product_id INTEGER NOT NULL,
            kind INTEGER NOT NULL,
            qty INTEGER NOT NULL,
            unit_cost INTEGER,
            ref_id INTEGER,
            reason TEXT,
            staff_id INTEGER,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL
        )""",
        "CREATE INDEX stock_movement_product ON stock_movement(product_id, hlc)",
        "CREATE INDEX stock_movement_hlc ON stock_movement(hlc)",
        // v2: expected + unit_cost are what the app had just before the count (variance reports).
        """CREATE TABLE stock_count (
            id INTEGER PRIMARY KEY,
            product_id INTEGER NOT NULL,
            qty INTEGER NOT NULL,
            session_id INTEGER,
            staff_id INTEGER,
            note TEXT,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL,
            expected INTEGER,
            unit_cost INTEGER
        )""",
        "CREATE INDEX stock_count_product ON stock_count(product_id, hlc)",
        // Not partial: session lists count rows in a correlated subquery (3.8 ignores partial indexes there, D-021).
        "CREATE INDEX stock_count_session ON stock_count(session_id, hlc)",
        """CREATE TABLE purchase (
            id INTEGER PRIMARY KEY,
            supplier_id INTEGER,
            ref_no TEXT,
            total INTEGER NOT NULL DEFAULT 0,
            note TEXT,
            staff_id INTEGER,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL
        )""",
        "CREATE INDEX purchase_at ON purchase(at)",
        "CREATE INDEX purchase_supplier ON purchase(supplier_id, at)",
        """CREATE TABLE purchase_line (
            id INTEGER PRIMARY KEY,
            purchase_id INTEGER NOT NULL REFERENCES purchase(id) ON DELETE CASCADE,
            product_id INTEGER NOT NULL,
            qty INTEGER NOT NULL,
            unit_cost INTEGER NOT NULL,
            total INTEGER NOT NULL
        )""",
        "CREATE INDEX purchase_line_purchase ON purchase_line(purchase_id)",
        // v3: shift_id = the shift whose drawer took a cash repayment (D-038).
        """CREATE TABLE credit_entry (
            id INTEGER PRIMARY KEY,
            customer_id INTEGER NOT NULL,
            kind INTEGER NOT NULL,
            amount INTEGER NOT NULL,
            sale_id INTEGER,
            method_id INTEGER,
            staff_id INTEGER,
            note TEXT,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL,
            shift_id INTEGER
        )""",
        "CREATE INDEX credit_entry_customer ON credit_entry(customer_id, hlc)",
        "CREATE INDEX credit_entry_shift ON credit_entry(shift_id) WHERE shift_id IS NOT NULL",
        """CREATE TABLE audit_log (
            id INTEGER PRIMARY KEY,
            action INTEGER NOT NULL,
            staff_id INTEGER,
            approved_by INTEGER,
            entity INTEGER,
            entity_id INTEGER,
            amount INTEGER,
            detail TEXT,
            at INTEGER NOT NULL,
            hlc INTEGER NOT NULL
        )""",
        "CREATE INDEX audit_at ON audit_log(at)",
        "CREATE INDEX audit_action ON audit_log(action, at)",

        // ---------- DERIVED: caches rebuildable from events ----------
        """CREATE TABLE stock_level (
            product_id INTEGER PRIMARY KEY,
            qty INTEGER NOT NULL DEFAULT 0,
            count_hlc INTEGER NOT NULL DEFAULT 0,
            count_dev INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE customer_balance (
            customer_id INTEGER PRIMARY KEY,
            balance INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE sum_day (
            day INTEGER PRIMARY KEY,
            sale_count INTEGER NOT NULL DEFAULT 0,
            refund_count INTEGER NOT NULL DEFAULT 0,
            void_count INTEGER NOT NULL DEFAULT 0,
            gross INTEGER NOT NULL DEFAULT 0,
            discount INTEGER NOT NULL DEFAULT 0,
            net_ex INTEGER NOT NULL DEFAULT 0,
            tax INTEGER NOT NULL DEFAULT 0,
            rounding INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            refund_total INTEGER NOT NULL DEFAULT 0,
            items INTEGER NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE sum_day_product (
            day INTEGER NOT NULL,
            product_id INTEGER NOT NULL,
            category_id INTEGER,
            qty INTEGER NOT NULL DEFAULT 0,
            net_ex INTEGER NOT NULL DEFAULT 0,
            tax INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (day, product_id)
        ) WITHOUT ROWID""",
        "CREATE INDEX sum_day_product_p ON sum_day_product(product_id, day)",
        // v4: per-month product totals (month = yyyymm), so long-range reports read 12 rows a year per product (D-043).
        """CREATE TABLE sum_month_product (
            month INTEGER NOT NULL,
            product_id INTEGER NOT NULL,
            category_id INTEGER,
            qty INTEGER NOT NULL DEFAULT 0,
            net_ex INTEGER NOT NULL DEFAULT 0,
            tax INTEGER NOT NULL DEFAULT 0,
            cost INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (month, product_id)
        ) WITHOUT ROWID""",
        """CREATE TABLE sum_day_payment (
            day INTEGER NOT NULL,
            method_id INTEGER NOT NULL,
            kind INTEGER NOT NULL,
            amount INTEGER NOT NULL DEFAULT 0,
            count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (day, method_id)
        ) WITHOUT ROWID""",
        """CREATE TABLE sum_day_staff (
            day INTEGER NOT NULL,
            staff_id INTEGER NOT NULL,
            sale_count INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            net_ex INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (day, staff_id)
        ) WITHOUT ROWID""",

        // ---------- LOCAL: open bills, print queue, sync outbox ----------
        """CREATE TABLE cart (
            id INTEGER PRIMARY KEY,
            status INTEGER NOT NULL DEFAULT 0,
            label TEXT,
            customer_id INTEGER,
            staff_id INTEGER,
            bill_disc_kind INTEGER NOT NULL DEFAULT 0,
            bill_disc_value INTEGER NOT NULL DEFAULT 0,
            note TEXT,
            opened_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        "CREATE INDEX cart_status ON cart(status, updated_at)",
        """CREATE TABLE cart_line (
            id INTEGER PRIMARY KEY,
            cart_id INTEGER NOT NULL REFERENCES cart(id) ON DELETE CASCADE,
            line_no INTEGER NOT NULL,
            product_id INTEGER,
            name TEXT NOT NULL,
            barcode TEXT,
            unit TEXT,
            category_id INTEGER,
            sell_mode INTEGER NOT NULL DEFAULT 0,
            qty INTEGER NOT NULL,
            pack_qty INTEGER NOT NULL DEFAULT 1000,
            base_qty INTEGER,
            unit_price INTEGER NOT NULL,
            fixed_gross INTEGER,
            price_overridden INTEGER NOT NULL DEFAULT 0,
            disc_kind INTEGER NOT NULL DEFAULT 0,
            disc_value INTEGER NOT NULL DEFAULT 0,
            tax_rate_id INTEGER,
            tax_bp INTEGER NOT NULL DEFAULT 0,
            unit_cost INTEGER NOT NULL DEFAULT 0,
            track_stock INTEGER NOT NULL DEFAULT 1,
            added_at INTEGER NOT NULL
        )""",
        "CREATE INDEX cart_line_cart ON cart_line(cart_id, line_no)",
        """CREATE TABLE print_job (
            id INTEGER PRIMARY KEY,
            kind INTEGER NOT NULL,
            ref_id INTEGER,
            copies INTEGER NOT NULL DEFAULT 1,
            status INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""",
        "CREATE INDEX print_job_status ON print_job(status, id)",
        // v5 (D-045): this till's sealed sync segments, and per other till the last segment applied here.
        """CREATE TABLE sync_segment (
            seq INTEGER PRIMARY KEY,
            count INTEGER NOT NULL,
            first_hlc INTEGER NOT NULL,
            last_hlc INTEGER NOT NULL,
            size INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            uploaded_at INTEGER
        )""",
        """CREATE TABLE sync_cursor (
            dev INTEGER PRIMARY KEY,
            seq INTEGER NOT NULL,
            last_hlc INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        )""",
        SYNC_DEFERRED,
        // AUTOINCREMENT: seq must never be reused after rows are deleted (segment sealing).
        """CREATE TABLE outbox (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            hlc INTEGER NOT NULL,
            entity INTEGER NOT NULL,
            op INTEGER NOT NULL,
            row_id INTEGER,
            payload TEXT NOT NULL
        )""",
    ).map { normalize(it) }

    /** Tables by sync class (every table must appear exactly once; checked by SchemaTest). */
    val LWW_TABLES = listOf(
        "setting", "role", "staff", "tax_rate", "category", "product", "product_barcode",
        "supplier", "customer", "payment_method", "shift", "count_session", "promotion",
    )
    val EVENT_TABLES = listOf(
        "cash_movement", "sale", "sale_line", "payment", "sale_void", "stock_movement",
        "stock_count", "purchase", "purchase_line", "credit_entry", "audit_log",
    )
    val DERIVED_TABLES = listOf(
        "product_fts", "stock_level", "customer_balance", "sum_day", "sum_day_product", "sum_month_product",
        "sum_day_payment", "sum_day_staff",
    )
    val LOCAL_TABLES = listOf(
        "meta", "cart", "cart_line", "print_job", "outbox", "sync_segment", "sync_cursor", "sync_deferred",
    )

    /** Tables whose `id` values come from this device's IdAllocator. */
    val GENERATED_ID_TABLES = LWW_TABLES.filter { it != "setting" } + EVENT_TABLES

    /** Collapses whitespace so snapshots and sqlite_master comparisons are stable. */
    fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")
        .replace("( ", "(").replace(" )", ")")
}
