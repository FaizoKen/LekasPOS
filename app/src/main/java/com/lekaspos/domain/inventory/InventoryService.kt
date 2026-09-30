package com.lekaspos.domain.inventory

import android.util.JsonReader
import android.util.JsonToken
import com.lekaspos.app.AppGraph
import com.lekaspos.core.inventory.AdjustReason
import com.lekaspos.core.inventory.ReceiveDraft
import com.lekaspos.core.inventory.ReceiveLine
import com.lekaspos.core.model.Perm
import com.lekaspos.data.db.Meta
import com.lekaspos.data.purchase.PurchaseDao
import com.lekaspos.data.purchase.PurchaseIn
import com.lekaspos.data.purchase.PurchaseLineIn
import com.lekaspos.data.stock.CountSessionDao
import com.lekaspos.data.stock.StockDao
import com.lekaspos.data.sync.Outbox
import com.lekaspos.domain.sale.ActionRefused
import com.lekaspos.util.Log
import java.io.StringReader

/**
 * Stock work (Phase 3): receiving deliveries, adjustments, opening stock and stock counts. Every
 * action checks MANAGE_STOCK and runs in one write transaction with its sync events.
 */
class InventoryService(private val graph: AppGraph) {

    private fun allowed() {
        if (!graph.permissions.allowed(Perm.MANAGE_STOCK)) throw ActionRefused(ActionRefused.Reason.NOT_ALLOWED)
    }

    /**
     * Records a delivery (purchase, stock in, cost update) and clears the saved draft. The draft
     * must still be stored: once it has been received (or discarded) it is gone, so a second
     * confirmation of the same delivery (a double tap) is refused instead of adding it twice.
     */
    suspend fun receive(d: ReceiveDraft): Long {
        allowed()
        require(!d.isEmpty) { "nothing received" }
        val staff = graph.staff.staffId
        return graph.db().write(reserveIds = d.lines.size * 2L + 16L) { tx ->
            if (Meta.get(tx.db, DRAFT_KEY).isNullOrEmpty()) throw ActionRefused(ActionRefused.Reason.NOT_FOUND)
            val id = PurchaseDao.commit(
                tx,
                PurchaseIn(d.supplierId, d.refNo, d.note, d.lines.map { PurchaseLineIn(it.productId, it.qty, it.unitCost, it.total) }),
                staff, System.currentTimeMillis(),
            )
            Meta.put(tx.db, DRAFT_KEY, null)
            id
        }
    }

    /** A manual stock change: [qty] > 0 as entered; the reason decides in or out (or [removing]). */
    suspend fun adjust(productId: Long, reason: AdjustReason, qty: Long, removing: Boolean, note: String?): Long {
        allowed()
        val delta = reason.delta(qty, removing)
        val staff = graph.staff.staffId
        return graph.db().write(reserveIds = 4L) { tx ->
            StockDao.insertMovement(tx, productId, reason.kind, delta, null, null, reason.encode(note), staff, System.currentTimeMillis())
        }
    }

    suspend fun startCount(name: String, categoryId: Long?): Long {
        allowed()
        val staff = graph.staff.staffId
        return graph.db().write(reserveIds = 4L) { tx -> CountSessionDao.create(tx, name, categoryId, staff, System.currentTimeMillis()) }
    }

    /** Records a counted quantity; it becomes the product's stock at once (D-035). */
    suspend fun count(sessionId: Long, productId: Long, qty: Long) {
        allowed()
        require(qty >= 0L) { "a count cannot be negative" }
        val staff = graph.staff.staffId
        graph.db().write(reserveIds = 4L) { tx -> StockDao.insertCount(tx, productId, qty, sessionId, staff, null, System.currentTimeMillis()) }
    }

    suspend fun finishCount(sessionId: Long) {
        allowed()
        graph.db().write(reserveIds = 0L) { tx -> CountSessionDao.finish(tx, sessionId, System.currentTimeMillis()) }
    }

    // ------------------------------------------------------------------ receiving draft

    /** The delivery being entered, kept in `meta` so a crash or call never loses it. */
    suspend fun loadDraft(): ReceiveDraft = graph.db().read { r ->
        val json = Meta.get(r, DRAFT_KEY)
        if (json.isNullOrEmpty()) {
            ReceiveDraft()
        } else {
            try {
                decode(json)
            } catch (e: Exception) {
                Log.w("Discarding an unreadable delivery draft", e)
                ReceiveDraft()
            }
        }
    }

    suspend fun saveDraft(d: ReceiveDraft) {
        val json = if (d.isEmpty && d.supplierId == null && d.refNo.isEmpty() && d.note.isEmpty()) null else encode(d)
        graph.db().write(reserveIds = 0L) { tx -> Meta.put(tx.db, DRAFT_KEY, json) }
    }

    companion object {
        private const val DRAFT_KEY = "draft.receive"

        fun encode(d: ReceiveDraft): String = Outbox.json { w ->
            w.beginObject()
            w.name("supplier")
            if (d.supplierId == null) w.nullValue() else w.value(d.supplierId)
            w.name("ref").value(d.refNo)
            w.name("note").value(d.note)
            w.name("lines").beginArray()
            for (l in d.lines) {
                w.beginObject()
                w.name("key").value(l.key)
                w.name("product").value(l.productId)
                w.name("name").value(l.name)
                w.name("unit").value(l.unit)
                w.name("qty").value(l.qty)
                w.name("cost").value(l.unitCost)
                w.name("total").value(l.total)
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }

        fun decode(json: String): ReceiveDraft {
            var supplier: Long? = null
            var ref = ""
            var note = ""
            val lines = ArrayList<ReceiveLine>()
            JsonReader(StringReader(json)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "supplier" -> supplier = if (r.peek() == JsonToken.NULL) r.nextNull().let { null } else r.nextLong()
                        "ref" -> ref = r.nextString()
                        "note" -> note = r.nextString()
                        "lines" -> {
                            r.beginArray()
                            while (r.hasNext()) lines.add(line(r))
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            return ReceiveDraft(supplier, ref, note, lines)
        }

        private fun line(r: JsonReader): ReceiveLine {
            var key = 0L
            var product = 0L
            var name = ""
            var unit: String? = null
            var qty = 0L
            var cost = 0L
            var total = 0L
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "key" -> key = r.nextLong()
                    "product" -> product = r.nextLong()
                    "name" -> name = r.nextString()
                    "unit" -> unit = if (r.peek() == JsonToken.NULL) r.nextNull().let { null } else r.nextString()
                    "qty" -> qty = r.nextLong()
                    "cost" -> cost = r.nextLong()
                    "total" -> total = r.nextLong()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return ReceiveLine(key, product, name, unit, qty, cost, total)
        }
    }
}
