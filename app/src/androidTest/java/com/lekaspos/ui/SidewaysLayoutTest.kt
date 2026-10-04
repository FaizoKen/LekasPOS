package com.lekaspos.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.R
import com.lekaspos.ui.common.FitButton
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.PadDialog
import com.lekaspos.ui.common.PinPad
import com.lekaspos.ui.common.keyHeightPx
import com.lekaspos.ui.common.sideways
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone held sideways (D-063): the payment, a keypad dialog, a PIN dialog and the sale's result fit its
 * short height whole — every key and button shows without scrolling (they were below the fold).
 */
@RunWith(AndroidJUnit4::class)
class SidewaysLayoutTest {

    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = base.resources.displayMetrics.density

    /** A phone held sideways: [widthDp] × [heightDp] (the status bar off the height). */
    private fun phone(widthDp: Int, heightDp: Int = 336): Context {
        val c = Configuration(base.resources.configuration)
        c.screenWidthDp = widthDp
        c.screenHeightDp = heightDp
        c.orientation = Configuration.ORIENTATION_LANDSCAPE
        return ContextThemeWrapper(base.createConfigurationContext(c), R.style.Theme_Lekas)
    }

    /** A modern phone (780dp wide sideways) and an older 16:9 one (640dp). */
    private val phones = listOf(780, 640)

    /** The room a dialog's body has on such a phone: 94% of the width, less the dialog frame. */
    private fun bodyWidth(widthDp: Int) = ((widthDp * 0.94f - FRAME_DP) * density).toInt()

    private fun measure(v: View, widthDp: Int): View {
        val w = bodyWidth(widthDp)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, w, v.measuredHeight)
        return v
    }

    private fun assertFits(v: View, label: String) {
        assertTrue(v.measuredHeight <= (MAX_BODY_DP * density).toInt(), "$label fits the height: ${(v.measuredHeight / density).toInt()}dp")
    }

    /** Every button under [root] lies inside it and is at least 48dp each way. */
    private fun assertButtonsWhole(root: View, label: String) {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is Button) {
                assertTrue(v.width >= (48 * density).toInt() - 1 && v.height >= (48 * density).toInt() - 1, "a button of $label is big enough")
                var x = 0
                var y = 0
                var p: View = v
                while (p !== root) {
                    x += p.left
                    y += p.top
                    p = p.parent as View
                }
                assertTrue(x >= 0 && y >= 0 && x + v.width <= root.width && y + v.height <= root.height, "a button of $label inside")
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    @Test
    fun aPhoneHeldSidewaysIsSideways() {
        val ctx = phone(780)
        assertTrue(ctx.sideways())
        assertEquals((50 * density).toInt(), ctx.keyHeightPx(56)) // a little lower on its short height
        val tablet = phone(1280, 752)
        assertTrue(tablet.sideways())
        assertEquals((56 * density).toInt(), tablet.keyHeightPx(56)) // a tablet has the room
        val upright = Configuration(base.resources.configuration).apply {
            screenWidthDp = 360
            screenHeightDp = 740
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        assertFalse(base.createConfigurationContext(upright).sideways())
    }

    @Test
    fun thePaymentFitsWhole() {
        for (w in phones) {
            val ctx = phone(w)
            val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_payment_wide, null)
            root.findViewById<FrameLayout>(R.id.pay_keypad).addView(Keypad(ctx, 9) {}.view)
            // As PaymentDialog builds them: below 720dp two notes and two methods to a row, else three.
            val narrow = w < 720
            val quick = root.findViewById<LinearLayout>(R.id.pay_quick)
            for (t in if (narrow) listOf("Exact", "RM60", "RM100") else listOf("Exact", "RM50", "RM60", "RM100")) {
                quick.addView(FitButton(ctx, null, 0, R.style.Widget_Lekas_Button_Secondary).apply { text = t; minWidth = 0 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // Six payment methods.
            val methods = root.findViewById<LinearLayout>(R.id.pay_methods)
            for (row in listOf("Cash", "Card", "E-wallet / QR", "DuitNow QR", "Touch 'n Go", "Bank transfer").chunked(if (narrow) 2 else 3)) {
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (m in row) line.addView(Button(ctx, null, 0, R.style.Widget_Lekas_Button_Secondary).apply { text = m; minWidth = 0 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                methods.addView(line)
            }
            root.findViewById<TextView>(R.id.pay_total).text = "RM 1,234.50"
            root.findViewById<TextView>(R.id.pay_amount).text = "RM 1,300.00"
            root.findViewById<TextView>(R.id.pay_change).apply { text = "Change RM 65.50"; visibility = View.VISIBLE }
            measure(root, w)
            assertFits(root, "payment at ${w}dp")
            assertButtonsWhole(root, "payment at ${w}dp")
        }
    }

    @Test
    fun aKeypadDialogAndAPinDialogFitWhole() {
        for (w in phones) {
            val ctx = phone(w)
            val amount = PadDialog(ctx, "Quantity: Milo 1kg")
                .info(TextView(ctx, null, 0, R.style.Text_Lekas_Caption).apply { text = "Now 12 pcs. Items already on the bill keep their price." })
                .info(TextView(ctx, null, 0, R.style.Text_Lekas_Display).apply { text = "RM 12.00" })
                .pad(Keypad(ctx, 9) {}.view)
                .neutral("Count cash") {}
                .negative("Cancel")
                .positive("OK") {}
                .sidewaysBody()
            measure(amount, w)
            assertFits(amount, "amount at ${w}dp")
            assertButtonsWhole(amount, "amount at ${w}dp")
            val pin = PinPad(ctx) {}
            val approval = PadDialog(ctx, "Manager approval")
                .info(TextView(ctx, null, 0, R.style.Text_Lekas_Body).apply { text = "Give discounts needs a manager's approval." })
                .info(TextView(ctx, null, 0, R.style.Text_Lekas_Section).apply { text = "Haji Abdul Rahman" })
                .info(pin.display)
                .pad(pin.keypad)
                .negative("Cancel")
                .sidewaysBody()
            measure(approval, w)
            assertFits(approval, "PIN at ${w}dp")
            assertButtonsWhole(approval, "PIN at ${w}dp")
        }
    }

    @Test
    fun theSalesResultFitsWhole() {
        for (w in phones) {
            val ctx = phone(w)
            val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_result_wide, null)
            v.findViewById<TextView>(R.id.result_label).text = "Change"
            v.findViewById<TextView>(R.id.result_amount).text = "RM 65.50"
            v.findViewById<TextView>(R.id.result_received).apply { text = "Received RM 1,300.00 · total RM 1,234.50"; visibility = View.VISIBLE }
            v.findViewById<TextView>(R.id.result_receipt).text = "Receipt 01-000123"
            v.findViewById<TextView>(R.id.result_print_state).text = "Printing the receipt…"
            v.findViewById<Button>(R.id.result_print).text = "Print a copy"
            measure(v, w)
            assertFits(v, "result at ${w}dp")
            assertButtonsWhole(v, "result at ${w}dp")
        }
    }

    private companion object {
        /** The dialog's frame each side together (its background's insets). */
        const val FRAME_DP = 32

        /** A 336dp-high screen less the dialog's frame and some room above and below. */
        const val MAX_BODY_DP = 290
    }
}
