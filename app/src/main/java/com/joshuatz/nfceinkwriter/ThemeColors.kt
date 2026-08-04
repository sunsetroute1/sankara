package com.joshuatz.nfceinkwriter

import android.content.Context
import androidx.annotation.AttrRes

object ThemeColors {

    fun resolve(context: Context, @AttrRes attr: Int): Int {
        val typedArray = context.theme.obtainStyledAttributes(intArrayOf(attr))
        val color = typedArray.getColor(0, 0)
        typedArray.recycle()
        return color
    }
}
