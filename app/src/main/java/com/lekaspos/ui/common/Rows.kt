package com.lekaspos.ui.common

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lekaspos.R
import com.lekaspos.ui.colorOf

class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
    val title: TextView = v.findViewById(R.id.row_title)
    val subtitle: TextView = v.findViewById(R.id.row_subtitle)
    val value: TextView = v.findViewById(R.id.row_value)
    val tag: TextView = v.findViewById(R.id.row_tag)
    private val icon: ImageView = v.findViewById(R.id.row_icon)
    private val thumb: View = v.findViewById<View>(R.id.row_thumb).apply { clipToOutline = true } // the picture's round corners
    private val thumbInitials: TextView = v.findViewById(R.id.row_thumb_initials)

    /** The picture in [thumb] (shown by [com.lekaspos.ui.common.PictureCache.show]). */
    val thumbPicture: ImageView = v.findViewById(R.id.row_thumb_picture)

    /**
     * Standard two-line row with an optional value on the right and a small tag; [icon] (a drawable, 0 = none)
     * in a soft circle on the left, so a hub's rows are found by eye before they are read.
     */
    fun set(
        title: CharSequence,
        subtitle: CharSequence? = null,
        value: CharSequence? = null,
        tag: CharSequence? = null,
        icon: Int = 0,
    ) {
        if (icon != 0) this.icon.setImageResource(icon)
        this.icon.visibility = if (icon != 0) View.VISIBLE else View.GONE
        thumb.visibility = View.GONE
        this.title.text = title
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        this.value.text = value
        this.value.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        this.tag.text = tag
        this.tag.visibility = if (tag.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * After [set]: a product's small square on the left, like its tile on the selling screen — [initials] on its
     * colour ([argb], null = none); the caller then shows the picture in [thumbPicture] if it has one.
     */
    fun thumb(initials: String, argb: Int?) {
        thumb.visibility = View.VISIBLE
        val ctx = thumb.context
        (thumb.background.mutate() as? GradientDrawable)?.setColor(argb ?: ctx.colorOf(R.color.chip_bg))
        thumbInitials.text = initials
        thumbInitials.setTextColor(ctx.colorOf(if (argb != null) R.color.text_on_brand else R.color.text_secondary))
    }
}

/** A group's heading in a [RowAdapter] list ([RowAdapter.header]). */
class SectionHolder(v: View) : RecyclerView.ViewHolder(v) {
    val title: TextView = v.findViewById(R.id.section_title)
}

/**
 * A list of simple rows; the screens only say how one item is shown. An item for which [header] gives a text is a
 * group's heading instead (not tappable).
 */
class RowAdapter<T>(
    private val bind: (RowHolder, T) -> Unit,
    private val onClick: (T) -> Unit,
    private val onLongClick: ((T) -> Unit)? = null,
    private val header: ((T) -> CharSequence?)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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

    override fun getItemViewType(position: Int): Int =
        if (header?.invoke(items[position]) != null) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            SectionHolder(inflater.inflate(R.layout.item_section, parent, false))
        } else {
            RowHolder(inflater.inflate(R.layout.item_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is SectionHolder) {
            holder.title.text = header?.invoke(item)
            return
        }
        bind(holder as RowHolder, item)
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

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_HEADER = 1
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
