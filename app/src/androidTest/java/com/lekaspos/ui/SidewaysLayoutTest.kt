package com.lekaspos.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.R
import com.lekaspos.ui.common.Keypad
import com.lekaspos.ui.common.PadDialog
import com.lekaspos.ui.common.PinPad
import com.lekaspos.ui.common.keyHeightPx
import com.lekaspos.ui.common.sideways
import com.lekaspos.ui.sell.PaymentViews
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone held sideways (D-063): the payment, a keypad dialog, a PIN dialog and the sale's result fit its
 * short height whole — every key and button shows without scrolling (they were below the fold). The payment
 * also fits a 5-inch phone upright (D-064).
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

    /** The payment as PaymentDialog builds it (D-064): six ways to pay, the notes for RM1,234.50. */
    private fun payment(ctx: Context): PaymentViews {
        val v = PaymentViews(ctx, ctx.sideways())
        v.keypad.addView(Keypad(ctx, 9) {}.view)
        v.notes(listOf("RM1,234.50", "RM1,235", "RM1,240", "RM1,250", "RM1,300"), "Other amount", {}, {})
        val methods = listOf("Cash", "Card", "E-wallet / QR", "DuitNow QR", "Touch 'n Go", "Bank transfer")
        v.methods(methods.drop(1)) {}
        v.partMethods(methods, 0) {}
        v.total.text = "RM 1,234.50"
        v.due.text = "Still to pay RM 1,234.50"
        v.amountLabel.text = "Cash the customer gave"
        v.amount.text = "RM 1,300.00"
        v.change.apply { text = "Change RM 65.50"; visibility = View.VISIBLE }
        return v
    }

    /** Each step of [v]: the ways to pay, the keypad for cash, the keypad for a part of a split payment. */
    private fun eachStep(v: PaymentViews, check: (String) -> Unit) {
        v.showStep(amount = false)
        check("ways to pay")
        v.showStep(amount = true)
        v.done.visibility = View.VISIBLE
        v.part.visibility = View.GONE
        check("cash amount")
        v.done.visibility = View.GONE
        v.part.visibility = View.VISIBLE
        check("split amount")
    }

    @Test
    fun thePaymentFitsWhole() {
        for (w in phones) {
            val v = payment(phone(w))
            eachStep(v) { step ->
                measure(v.root, w)
                assertFits(v.root, "payment ($step) at ${w}dp")
                assertButtonsWhole(v.root, "payment ($step) at ${w}dp")
            }
        }
    }

    /** Upright on a 5-inch phone (360 × 640dp) with the usual ways to pay: no step needs scrolling (D-064). */
    @Test
    fun thePaymentFitsAPhoneUpright() {
        val c = Configuration(base.resources.configuration).apply {
            screenWidthDp = 360
            screenHeightDp = 592
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        val ctx = ContextThemeWrapper(base.createConfigurationContext(c), R.style.Theme_Lekas)
        val v = PaymentViews(ctx, ctx.sideways())
        assertFalse(v.wide)
        v.keypad.addView(Keypad(ctx, 9) {}.view)
        v.notes(listOf("RM23.45", "RM25", "RM30", "RM50", "RM100"), "Other amount", {}, {})
        v.methods(listOf("Card", "E-wallet / QR")) {}
        v.partMethods(listOf("Cash", "Card", "E-wallet / QR"), 0) {}
        v.total.text = "RM 23.45"
        v.due.text = "To pay RM 23.45"
        v.amountLabel.text = "Cash the customer gave"
        v.amount.text = "RM 50.00"
        v.change.apply { text = "Change RM 26.55"; visibility = View.VISIBLE }
        eachStep(v) { step ->
            measure(v.root, 360)
            assertTrue(v.root.measuredHeight <= (UPRIGHT_BODY_DP * density).toInt(), "payment ($step) upright: ${(v.root.measuredHeight / density).toInt()}dp")
            assertButtonsWhole(v.root, "payment ($step) upright")
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

        /** A 640dp-high phone upright, less the status and navigation bars, the dialog's frame and some room. */
        const val UPRIGHT_BODY_DP = 520
    }
}
