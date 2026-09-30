package com.lekaspos.ui.catalog

import android.os.Bundle
import android.text.InputType
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.core.model.Perm
import com.lekaspos.data.catalog.Category
import com.lekaspos.data.catalog.CategoryDao
import com.lekaspos.ui.common.Dialogs
import com.lekaspos.ui.common.RowAdapter
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.visible
import kotlinx.coroutines.CoroutineScope

/** Product categories (the selling screen's catalogue tabs): add, rename, delete. */
class CategoriesActivity : ScreenActivity() {

    private lateinit var empty: TextView
    private val adapter = RowAdapter<Category>(
        bind = { h, c -> h.set(c.name) },
        onClick = { edit(it) },
        onLongClick = { delete(it) },
    )

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
        Dialogs.input(
            this, getString(if (c == null) R.string.category_add else R.string.category_edit), getString(R.string.category_name),
            initial = c?.name ?: "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        ) { name ->
            if (name.isEmpty()) return@input false
            launchUi {
                graph.db().write(reserveIds = 4L) { tx ->
                    val now = System.currentTimeMillis()
                    if (c == null) CategoryDao.insert(tx, name, 0, 0, now) else CategoryDao.update(tx, c, c.copy(name = name), now)
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
}
