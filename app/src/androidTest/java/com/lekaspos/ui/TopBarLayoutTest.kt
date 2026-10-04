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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selling screen's top bar (2026-10 review): with every pill showing, in Malay, on the
 * narrowest phones, the Menu button stays whole at the top right and no pill is cut off or
 * covers another.
 */
@RunWith(AndroidJUnit4::class)
class TopBarLayoutTest {

    private val ctx = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_Lekas)
    private val density = ctx.resources.displayMetrics.density

    private fun bar(): ViewGroup {
        val bar = LayoutInflater.from(ctx).inflate(R.layout.sell_top_bar, null) as ViewGroup
        bar.findViewById<TextView>(R.id.title).text = "Kedai Runcit Haji Abdul Rahman & Anak-Anak"
        for ((id, text) in listOf(
            R.id.staff_chip to "Siti Nur Aisyah binti Abdullah",
            R.id.held_pill to "2 ditahan",
            R.id.sync_state to "Sandaran: log masuk",
            R.id.printer_state to "Pencetak luar talian (12)",
            R.id.helper_pill to "Pengurus: Haji Abdul Rahman ✕",
        )) {
            bar.findViewById<TextView>(id).apply {
                this.text = text
                visibility = View.VISIBLE
            }
        }
        return bar
    }

    private fun laidOut(widthDp: Int): ViewGroup {
        val bar = bar()
        val width = (widthDp * density).toInt()
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        bar.layout(0, 0, width, bar.measuredHeight)
        return bar
    }

    private fun rect(v: View) = Rect(v.left, v.top, v.right, v.bottom)

    @Test
    fun menuStaysAndNoPillIsLostOnSmallPhones() {
        for (widthDp in listOf(320, 360, 411, 600, 800, 1280)) {
            val bar = laidOut(widthDp)
            val menu = bar.findViewById<View>(R.id.btn_menu)
            assertEquals(bar.width - bar.paddingRight, menu.right, "Menu at the right edge at ${widthDp}dp")
            assertTrue(menu.left >= bar.paddingLeft && menu.width >= (56 * density).toInt() - 1, "Menu whole at ${widthDp}dp")
            val pills = listOf(R.id.staff_chip, R.id.held_pill, R.id.sync_state, R.id.printer_state, R.id.helper_pill).map { bar.findViewById<View>(it) }
            for (p in pills) {
                assertTrue(p.width > 0 && p.left >= 0 && p.right <= bar.width && p.bottom <= bar.height, "pill inside the bar at ${widthDp}dp")
                assertTrue(!Rect.intersects(rect(p), rect(menu)), "pill under Menu at ${widthDp}dp")
            }
            for (i in pills.indices) for (j in i + 1 until pills.size) {
                assertTrue(!Rect.intersects(rect(pills[i]), rect(pills[j])), "pills overlap at ${widthDp}dp")
            }
        }
    }

    @Test
    fun withoutPillsTheBarIsOneLine() {
        val bar = bar()
        for (id in listOf(R.id.staff_chip, R.id.held_pill, R.id.sync_state, R.id.printer_state, R.id.helper_pill)) bar.findViewById<View>(id).visibility = View.GONE
        val width = (360 * density).toInt()
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals((56 * density).toInt(), bar.measuredHeight)
        bar.layout(0, 0, width, bar.measuredHeight)
        val title = bar.findViewById<View>(R.id.title)
        assertEquals(bar.findViewById<View>(R.id.btn_menu).left, title.right) // the name takes the rest of the line
    }
}
