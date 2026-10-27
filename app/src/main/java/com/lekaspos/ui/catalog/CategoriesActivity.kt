package com.lekaspos.ui.catalog

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.core.model.TileColor
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.ui.common.ColorPicker
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope

/**
 * Product categories (the selling screen's catalogue tabs): add, rename, delete, and their order — the arrows move a
 * category up or down among the selling screen's chips (D-086; they were always by name).
 */
class CategoriesActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private val adapter = CategoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = setScreen(getString(R.string.categories_title), R.layout.list_plain) ?: return
        addAction(R.drawable.ic_add, R.string.category_add) { edit(null) }
        empty = v.findViewById(R.id.list_empty)
        empty.setText(R.string.categories_empty)
        val list = v.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onStarted(scope: CoroutineScope) = reload()

    private fun reload() {
        launchUi {
            val items = graph.db().read { CategoryDao.list(it) }
            adapter.submit(items)
            empty.visible(items.isEmpty())
        }
    }

    private fun edit(c: Category?) = requireAccess(Perm.MANAGE_PRODUCTS) { editNow(c) }

    private fun editNow(c: Category?) {
        // Its colour: the tiles of its products that have none of their own (D-066).
        val colors = ColorPicker(this, c?.color ?: TileColor.NONE)
        Dialogs.input(
            this, getString(if (c == null) R.string.category_add else R.string.category_edit), getString(R.string.category_name),
            initial = c?.name ?: "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            below = colors.view,
        ) { name ->
            if (name.isEmpty()) return@input false
            val color = colors.chosen
            launchUi {
                graph.db().write(reserveIds = 4L) { tx ->
                    val now = System.currentTimeMillis()
                    if (c == null) {
                        CategoryDao.insert(tx, name, color, CategoryDao.nextSort(tx.db), now) // last once they are in order
                    } else {
                        CategoryDao.update(tx, c, c.copy(name = name, color = color), now)
                    }
                }
                reload()
            }
            true
        }
    }

    private fun delete(c: Category) = requireAccess(Perm.MANAGE_PRODUCTS) { deleteNow(c) }

    private fun deleteNow(c: Category) {
        Dialogs.confirm(this, getString(R.string.delete), getString(R.string.category_delete_confirm, c.name), getString(R.string.delete)) {
            launchUi {
                graph.db().write(reserveIds = 0L) { tx -> CategoryDao.delete(tx, c.id, System.currentTimeMillis()) }
                reload()
            }
        }
    }

    /** Moves [c] one place up ([by] −1) or down (+1): shown at once, then the whole order is saved. */
    private fun move(c: Category, by: Int) = requireAccess(Perm.MANAGE_PRODUCTS) {
        val order = adapter.items.toMutableList()
        val from = order.indexOfFirst { it.id == c.id }
        val to = from + by
        if (from < 0 || to !in order.indices) return@requireAccess
        order.add(to, order.removeAt(from))
        adapter.submit(order)
        val ids = order.map { it.id }
        launchUi {
            graph.db().write(reserveIds = 0L) { tx -> CategoryDao.reorder(tx, ids, System.currentTimeMillis()) }
            reload()
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.category_name)
        val up: ImageButton = v.findViewById(R.id.category_up)
        val down: ImageButton = v.findViewById(R.id.category_down)
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<Holder>() {
        var items: List<Category> = emptyList()
            private set

        @android.annotation.SuppressLint("NotifyDataSetChanged") // a short list, read again whole
        fun submit(list: List<Category>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_category_row, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = items[position]
            h.name.text = c.name
            h.name.setOnClickListener { edit(c) }
            h.name.setOnLongClickListener {
                delete(c)
                true
            }
            fun arrow(b: ImageButton, enabled: Boolean, by: Int) {
                b.isEnabled = enabled
                b.alpha = if (enabled) 1f else 0.3f
                b.setOnClickListener { move(c, by) }
            }
            arrow(h.up, position > 0, -1)
            arrow(h.down, position < items.size - 1, +1)
        }
    }
}
