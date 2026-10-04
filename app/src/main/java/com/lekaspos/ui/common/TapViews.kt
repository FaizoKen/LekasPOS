package com.lekaspos.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView

/*
 * Views whose buttons and rows show they are pressed the moment a finger lands. A scrolling container
 * holds that back for 100 ms, in case the touch becomes a scroll: a quick tap on a product tile or a
 * keypad key lit up only after the finger had left, and fast tapping felt slow (D-063). The tap itself
 * counts either way.
 */

/** The selling screen's lists (tiles, category chips, bill lines). */
class TapList @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    RecyclerView(context, attrs, defStyle) {

    override fun shouldDelayChildPressedState(): Boolean = false
}

/** A dialog's scrolling body holding a keypad (payment, amounts, PINs: [Dialogs.scrolling]). */
class TapScroll @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    ScrollView(context, attrs, defStyle) {

    override fun shouldDelayChildPressedState(): Boolean = false
}
