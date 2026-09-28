package com.lekaspos.ui.common

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R

class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
    val title: TextView = v.findViewById(R.id.row_title)
    val subtitle: TextView = v.findViewById(R.id.row_subtitle)
    val value: TextView = v.findViewById(R.id.row_value)
    val tag: TextView = v.findViewById(R.id.row_tag)

    /** Standard two-line row with an optional value on the right and a small tag. */
    fun set(title: CharSequence, subtitle: CharSequence? = null, value: CharSequence? = null, tag: CharSequence? = null) {
        this.title.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        this.value.text = value
        this.value.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        this.tag.text = tag
        this.tag.visibility = if (tag.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

/** A list of simple rows; the screens only say how one item is shown. */
class RowAdapter<T>(
    private val bind: (RowHolder, T) -> Unit,
    private val onClick: (T) -> Unit,
    private val onLongClick: ((T) -> Unit)? = null,
) : RecyclerView.Adapter<RowHolder>() {

    var items: List<T> = emptyList()
        private set

    @SuppressLint("NotifyDataSetChanged") // a new page set replaces the old one
    fun submit(list: List<T>) {
        items = list
        notifyDataSetChanged()
    }

    fun append(more: List<T>) {
        if (more.isEmpty()) return
        val start = items.size
        items = items + more
        notifyItemRangeInserted(start, more.size)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val item = items[position]
        bind(holder, item)
        holder.itemView.setOnClickListener { onClick(item) }
        val long = onLongClick
        if (long != null) {
            holder.itemView.setOnLongClickListener {
                long(item)
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }
}

/** Calls [loadMore] when the list is scrolled near its end (keyset paging). */
fun RecyclerView.onNearEnd(threshold: Int = 10, loadMore: () -> Unit) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0 && dx <= 0) return
            val lm = rv.layoutManager as? LinearLayoutManager ?: return
            if (lm.findLastVisibleItemPosition() >= lm.itemCount - threshold) loadMore()
        }
    })
}
