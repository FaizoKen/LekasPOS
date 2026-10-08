package com.lekaspos.core.display

import com.lekaspos.core.cart.Cart
import com.lekaspos.core.cart.CartItem
import com.lekaspos.core.model.SellMode
import com.lekaspos.core.pricing.Discount
import com.lekaspos.core.pricing.PricedCart
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/** D-069: what the customer screen shows. */
class CustomerViewTest {

    private val milo = CartItem(key = 1L, productId = 10L, name = "Milo", qty = 2_000L, unitPrice = 3_890L)
    private val udang = CartItem(key = 2L, productId = 11L, name = "Udang", unit = "kg", sellMode = SellMode.WEIGHT, qty = 535L, unitPrice = 2_990L)
    private val now = 1_760_000_000_000L

    private fun bill(vararg items: CartItem): Pair<List<CartItem>, PricedCart> {
        var c = Cart()
        for (i in items) c = c.add(i).cart
        return c.items to c.price(pricesIncludeTax = true)
    }

    @Test
    fun theBillWithTheItemJustAdded() {
        val (items, priced) = bill(milo, udang)
        val v = assertIs<CustomerView.Bill>(CustomerView.of(items, priced, lastKey = 1L, paying = false, locked = false, done = null, now = now))
        assertEquals(listOf("Milo", "Udang"), v.lines.map { it.name })
        assertEquals("Milo", v.last?.name) // the one changed last, not the one at the bottom
        assertEquals(7_780L, v.lines[0].amount)
        assertEquals(true, v.lines[1].weighed)
        assertEquals(3L, v.items) // two tins and one weighed item
        assertEquals(priced.total, v.total)
        assertEquals(false, v.paying)
        // The payment open: "Total to pay".
        assertEquals(true, assertIs<CustomerView.Bill>(CustomerView.of(items, priced, 2L, true, false, null, now)).paying)
        // A line discount is in its amount; a bill discount shows on its own.
        val discounted = Cart().add(milo).cart.setDiscount(1L, Discount.Amount(780L)).withBillDiscount(Discount.Amount(1_000L))
        val d = assertIs<CustomerView.Bill>(CustomerView.of(discounted.items, discounted.price(true), 1L, false, false, null, now))
        assertEquals(7_000L, d.lines[0].amount)
        assertEquals(1_000L, d.discount)
        assertEquals(6_000L, d.total)
    }

    @Test
    fun welcomeThanksAndLocked() {
        val (empty, nothing) = bill()
        assertEquals(CustomerView.Welcome, CustomerView.of(empty, nothing, 0L, false, false, null, now))
        // After the sale: the change and a thank-you, for a while.
        val done = CustomerView.Done(total = 5_960L, received = 10_000L, change = 4_040L, at = now - 5_000L)
        assertEquals(CustomerView.Thanks(5_960L, 10_000L, 4_040L), CustomerView.of(empty, nothing, 0L, false, false, done, now))
        assertEquals(CustomerView.Welcome, CustomerView.of(empty, nothing, 0L, false, false, done, now + CustomerView.THANKS_MS))
        // The next customer's first item ends it at once.
        val (items, priced) = bill(milo)
        assertIs<CustomerView.Bill>(CustomerView.of(items, priced, 1L, false, false, done, now))
        // A locked till shows the welcome, whatever waits on it.
        assertEquals(CustomerView.Welcome, CustomerView.of(items, priced, 1L, false, true, done, now))
        assertEquals(CustomerView.Welcome, CustomerView.of(empty, nothing, 0L, false, true, done, now))
    }
}
