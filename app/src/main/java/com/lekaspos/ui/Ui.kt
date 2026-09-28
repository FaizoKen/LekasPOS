package com.lekaspos.ui

import android.content.Context
import android.os.Build

/** Color resource lookup that also works on API 21–22 (Context.getColor is API 23+). */
fun Context.colorOf(id: Int): Int =
    if (Build.VERSION.SDK_INT >= 23) getColor(id) else @Suppress("DEPRECATION") resources.getColor(id)
