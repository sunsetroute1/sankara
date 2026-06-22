package com.joshuatz.nfceinkwriter

import android.graphics.Color

enum class EInkColorMode(val prefValue: String, val label: String) {
    BLACK_WHITE("bw", "Black & white"),
    THREE_COLOR("bwr", "3-color B/W/Red"),
    FOUR_COLOR("bwry", "4-color B/W/R/Y");

    fun palette(): IntArray = when (this) {
        BLACK_WHITE -> intArrayOf(Color.BLACK, Color.WHITE)
        THREE_COLOR -> intArrayOf(Color.BLACK, Color.WHITE, 0xFFCC0000.toInt())
        FOUR_COLOR -> intArrayOf(
            Color.BLACK,
            Color.WHITE,
            0xFFCC0000.toInt(),
            0xFFE6C200.toInt(),
        )
    }

    companion object {
        val DEFAULT = BLACK_WHITE

        fun fromPref(value: String?): EInkColorMode =
            entries.find { it.prefValue == value } ?: DEFAULT
    }
}
