package com.joshuatz.nfceinkwriter.nfc.discovery

import android.content.Context
import com.joshuatz.nfceinkwriter.EInkColorMode
import com.joshuatz.nfceinkwriter.ScreenSizesInPixels
import org.json.JSONArray

/**
 * Maps passive hardware codes to panel geometry. Loaded from assets and extensible
 * as community members report new passive modules.
 */
object PassiveHardwareCatalog {

    data class Entry(
        val hardwareCode: Int,
        val width: Int,
        val height: Int,
        val displayName: String,
        val colorMode: EInkColorMode?,
        val screenSizeKey: String?,
    )

    @Volatile
    private var cache: List<Entry>? = null

    fun resolve(context: Context, hardwareCode: Int): Entry {
        val entries = load(context)
        return entries.firstOrNull { it.hardwareCode == hardwareCode }
            ?: Entry(
                hardwareCode = hardwareCode,
                width = 240,
                height = 416,
                displayName = "Passive module (hw=0x${hardwareCode.toString(16)})",
                colorMode = EInkColorMode.FOUR_COLOR,
                screenSizeKey = "3.7\" INKZONE",
            )
    }

    fun load(context: Context): List<Entry> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = readFromAssets(context) + builtInFallbacks()
            cache = loaded.distinctBy { it.hardwareCode }
            return cache!!
        }
    }

    private fun builtInFallbacks(): List<Entry> = listOf(
        Entry(
            hardwareCode = 0,
            width = ScreenSizesInPixels["3.7\" INKZONE"]!!.first,
            height = ScreenSizesInPixels["3.7\" INKZONE"]!!.second,
            displayName = "INKZONE 3.7\"",
            colorMode = EInkColorMode.FOUR_COLOR,
            screenSizeKey = "3.7\" INKZONE",
        ),
    )

    private fun readFromAssets(context: Context): List<Entry> = try {
        context.assets.open("passive_hardware.json").bufferedReader().use { reader ->
            parseJson(JSONArray(reader.readText()))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseJson(array: JSONArray): List<Entry> {
        val list = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list.add(
                Entry(
                    hardwareCode = item.getInt("hardwareCode"),
                    width = item.getInt("width"),
                    height = item.getInt("height"),
                    displayName = item.getString("displayName"),
                    colorMode = item.optString("colorMode").takeIf { it.isNotEmpty() }
                        ?.let { EInkColorMode.fromPref(it) },
                    screenSizeKey = item.optString("screenSizeKey").takeIf { it.isNotEmpty() },
                ),
            )
        }
        return list
    }
}
