package com.lekaspos.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Edge-to-edge is enforced for targetSdk 35+ on Android 15+ (references/architecture.md §5):
 * pad the top bar by the status bar and the content root by the navigation bar / IME.
 * On older Android the decor already reserves these areas and the insets arrive as zero.
 */
object Insets {

    fun apply(root: View, topBar: View? = null) {
        val rootStart = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val barStart = topBar?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (left, top, right, bottom) = sides(insets)
            if (topBar != null && barStart != null) {
                topBar.setPadding(barStart[0], barStart[1] + top, barStart[2], barStart[3])
                v.setPadding(rootStart[0] + left, rootStart[1], rootStart[2] + right, rootStart[3] + bottom)
            } else {
                v.setPadding(rootStart[0] + left, rootStart[1] + top, rootStart[2] + right, rootStart[3] + bottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun sides(insets: WindowInsets): IntArray = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        intArrayOf(i.left, i.top, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }
}
