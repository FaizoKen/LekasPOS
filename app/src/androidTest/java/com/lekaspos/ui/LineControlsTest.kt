package com.lekaspos.ui

import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.R
import com.lekaspos.ui.sell.LineControls
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selected bill line's buttons (D-063): one line under the item on a phone — Remove, −, quantity,
 * +, More in that order — and two lines where they do not fit (a narrow bill pane), never a button cut
 * off, overlapping another or smaller than a selling-screen touch target.
 */
@RunWith(AndroidJUnit4::class)
class LineControlsTest {

    private val ctx = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Lekas)
    private val density = ctx.resources.displayMetrics.density
    private val ids = listOf(R.id.line_remove, R.id.line_minus, R.id.line_qty, R.id.line_plus, R.id.line_more)

    private fun controls(widthDp: Int, gone: List<Int> = emptyList(), qty: String = "12"): LineControls {
        val line = LayoutInflater.from(ctx).inflate(R.layout.item_cart_line, null) as ViewGroup
        val c = line.findViewById<LineControls>(R.id.controls)
        c.visibility = View.VISIBLE
        line.findViewById<TextView>(R.id.line_qty).text = qty
        for (id in gone) c.findViewById<View>(id).visibility = View.GONE
        val width = (widthDp * density).toInt()
        c.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        c.layout(0, 0, width, c.measuredHeight)
        return c
    }

    private fun rect(v: View) = Rect(v.left, v.top, v.right, v.bottom)

    /** Every shown button inside, apart from the others, at least 56dp high and 48dp wide. */
    private fun assertWhole(c: LineControls, label: String) {
        val shown = ids.map { c.findViewById<View>(it) }.filter { it.visibility != View.GONE }
        val touch = (56 * density).toInt() - 1
        for (v in shown) {
            assertTrue(v.left >= 0 && v.right <= c.width && v.bottom <= c.height, "${name(v)} inside ($label)")
            assertTrue(v.height >= touch && v.width >= (48 * density).toInt() - 1, "${name(v)} big enough ($label)")
        }
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            assertFalse(Rect.intersects(rect(shown[i]), rect(shown[j])), "${name(shown[i])} and ${name(shown[j])} apart ($label)")
        }
    }

    private fun name(v: View) = ctx.resources.getResourceEntryName(v.id)

    @Test
    fun oneLineOnAPhoneInTheOwnersOrder() {
        for (widthDp in listOf(336, 411, 600)) { // a 360dp phone's bill line, larger phones, a tablet's bill pane
            val c = controls(widthDp)
            assertTrue(c.oneLine, "one line at ${widthDp}dp")
            val views = ids.map { c.findViewById<View>(it) }
            for (v in views) assertEquals(views[0].top, v.top, "${name(v)} on the same line at ${widthDp}dp")
            for (i in 0 until views.size - 1) assertTrue(views[i].right <= views[i + 1].left, "order at ${widthDp}dp")
            // Remove is the farthest from +, with the widest gap.
            val removeGap = views[1].left - views[0].right
            assertTrue(removeGap > views[2].left - views[1].right, "Remove apart at ${widthDp}dp")
            assertWhole(c, "${widthDp}dp")
        }
    }

    @Test
    fun twoLinesWhenTheyDoNotFit() {
        // A phone turned with two panes leaves the bill about 250dp; a large font makes the words wider.
        val c = controls(230, qty = "1,000")
        assertFalse(c.oneLine)
        val minus = c.findViewById<View>(R.id.line_minus)
        val remove = c.findViewById<View>(R.id.line_remove)
        assertTrue(remove.top >= minus.bottom, "Remove and More below − quantity +")
        assertWhole(c, "230dp")
    }

    @Test
    fun withoutMoreOrAQuantityTheLineStillFills() {
        // A cashier (no discount or price change): no More — one line even on a narrow pane.
        val cashier = controls(260, gone = listOf(R.id.line_more))
        assertTrue(cashier.oneLine)
        assertEquals(cashier.width, cashier.findViewById<View>(R.id.line_plus).right)
        assertWhole(cashier, "cashier")
        // A label-price line: no − quantity +; Remove and More share the line.
        val label = controls(336, gone = listOf(R.id.line_minus, R.id.line_qty, R.id.line_plus))
        assertTrue(label.oneLine)
        assertEquals(label.width, label.findViewById<View>(R.id.line_more).right)
        assertWhole(label, "label price")
    }
}
