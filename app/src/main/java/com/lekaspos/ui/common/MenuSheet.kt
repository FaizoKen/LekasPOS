package com.lekaspos.ui.common

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lekaspos.R
import com.lekaspos.ui.colorOf

/**
 * The selling screen's Menu (D-074): every job as a tile with an icon over its word, in groups ("This bill", "Till",
 * "Manage shop"), all on one sheet. The platform PopupMenu it replaces showed no icons, and the owner's and
 * manager's screens were one level further down, behind "Manage shop ›". Ids are the labels' string ids, as before.
 */
class MenuSheet(private val a: Activity) {

    private class Item(val id: Int, val icon: Int, val badge: String?, val danger: Boolean)

    private val groups = ArrayList<Pair<String, ArrayList<Item>>>()

    /** Starts a group headed [title]; the items added next belong to it. Empty groups are not shown. */
    fun group(title: Int) {
        groups += a.getString(title) to ArrayList()
    }

    /** A tile labelled with string [id] (also what [show] reports); [badge] a count on its corner. */
    fun item(id: Int, icon: Int, badge: String? = null, danger: Boolean = false) {
        check(groups.isNotEmpty()) { "group() first" }
        groups.last().second += Item(id, icon, badge, danger)
    }

    /**
     * Shows the sheet under [anchor]; [onPick] gets the tile's id after the sheet closed. [onKey] sees the hardware
     * keys first (a scanner): when it takes one the sheet closes and the rest of the scan goes to the screen.
     */
    fun show(anchor: View, onKey: (KeyEvent) -> Boolean, onPick: (Int) -> Unit): Dialog {
        val d = Dialog(a, R.style.Theme_Lekas_Menu)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val density = a.resources.displayMetrics.density
        val frame = Rect().also { a.window.decorView.getWindowVisibleDisplayFrame(it) }
        val margin = (8 * density).toInt()
        val width = minOf(frame.width() - 2 * margin, (MAX_WIDTH_DP * density).toInt())
        val inner = width - (2 * PAD_DP * density).toInt()
        val columns = (inner / (TILE_DP * density)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (PAD_DP * density).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }
        var picked = false
        for ((title, items) in groups) {
            if (items.isEmpty()) continue
            content.addView(heading(content, title))
            for (row in items.chunked(columns)) {
                val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                for (item in row) {
                    line.addView(tile(line, item) {
                        if (picked) return@tile // a double tap: one job
                        picked = true
                        d.dismiss()
                        onPick(item.id)
                    })
                }
                // The last row's tiles keep the same width as the others; the fillers as tall as the row (a 1px one
                // squashed the row's buttons in the payment's grid).
                repeat(columns - row.size) {
                    line.addView(View(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).withMargins(density))
                }
                content.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        d.setContentView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = true
            addView(content)
        })
        d.window?.let { w ->
            w.setBackgroundDrawableResource(R.drawable.menu_sheet_bg)
            w.setGravity(Gravity.TOP or Gravity.END)
            val lp = w.attributes
            lp.width = width
            lp.x = margin
            // Just under the Menu button; taller than the room below it (a phone held sideways), it scrolls.
            val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
            val top = at[1] + anchor.height
            lp.y = (top - frame.top).coerceAtLeast(0)
            val room = frame.bottom - top - margin
            content.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            lp.height = if (room > 0 && content.measuredHeight > room) room else WindowManager.LayoutParams.WRAP_CONTENT
            w.attributes = lp
        }
        d.setCanceledOnTouchOutside(true)
        d.keys { e ->
            if (e.keyCode == KeyEvent.KEYCODE_BACK) return@keys false // closes the sheet
            val taken = onKey(e)
            if (taken && d.isShowing) d.dismiss()
            taken || DialogKeys.pressesFocused(e.keyCode)
        }
        if (Dialogs.canShow(a)) d.show()
        return d.trackedBy(a)
    }

    /** A group's heading, as in the Settings list (item_section), closer to the tiles. */
    private fun heading(parent: ViewGroup, title: String): View {
        val v = a.layoutInflater.inflate(R.layout.item_section, parent, false) as TextView
        v.text = title
        val density = a.resources.displayMetrics.density
        v.setPadding((4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
        return v
    }

    private fun tile(parent: ViewGroup, item: Item, onClick: () -> Unit): View {
        val v = a.layoutInflater.inflate(R.layout.item_menu_tile, parent, false)
        val label = a.getString(item.id)
        val icon = v.findViewById<ImageView>(R.id.menu_icon)
        icon.setImageResource(item.icon)
        val text = v.findViewById<TextView>(R.id.menu_label)
        text.text = label
        if (item.danger) {
            val red = a.colorOf(R.color.danger)
            icon.imageTintList = ColorStateList.valueOf(red)
            text.setTextColor(red)
        }
        val badge = v.findViewById<TextView>(R.id.menu_badge)
        if (item.badge != null) {
            badge.text = item.badge
            badge.visibility = View.VISIBLE
        }
        v.contentDescription = if (item.badge != null) "$label, ${item.badge}" else label
        v.setOnClickListener { onClick() }
        // As tall as the row's tallest (a label on two lines).
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).withMargins(a.resources.displayMetrics.density)
        return v
    }

    private fun LinearLayout.LayoutParams.withMargins(density: Float) = apply {
        val m = (4 * density).toInt()
        setMargins(m, m, m, m)
    }

    private companion object {
        const val MAX_WIDTH_DP = 520
        const val PAD_DP = 8

        /** About the width of a tile: three on a phone held upright, four or five on a tablet. */
        const val TILE_DP = 104
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 5
    }
}
