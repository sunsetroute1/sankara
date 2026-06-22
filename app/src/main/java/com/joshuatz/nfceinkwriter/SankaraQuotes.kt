package com.joshuatz.nfceinkwriter

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader

/**
 * Cached Thomas Sankara quotes (English, with French originals in assets).
 */
object SankaraQuotes {
    data class Quote(val english: String, val french: String)

    @Volatile
    private var cache: List<Quote>? = null

    fun randomQuote(context: Context): Quote {
        val quotes = load(context)
        return quotes.random()
    }

    fun load(context: Context): List<Quote> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = readFromAssets(context)
            cache = loaded
            return loaded
        }
    }

    private fun readFromAssets(context: Context): List<Quote> {
        return try {
            context.assets.open("sankara_quotes.json").bufferedReader().use { reader ->
                parseJson(reader)
            }
        } catch (_: Exception) {
            fallbackQuotes()
        }
    }

    private fun parseJson(reader: BufferedReader): List<Quote> {
        val json = JSONArray(reader.readText())
        val list = ArrayList<Quote>(json.length())
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            val en = item.optString("en").trim()
            val fr = item.optString("fr").trim()
            if (en.isNotEmpty()) {
                list.add(Quote(en, fr))
            }
        }
        return if (list.isEmpty()) fallbackQuotes() else list
    }

    private fun fallbackQuotes(): List<Quote> = listOf(
        Quote(
            "Homeland or death, we shall overcome.",
            "Patrie ou mort, nous vaincrons.",
        ),
        Quote(
            "We must dare to invent the future.",
            "Il faut oser inventer l'avenir.",
        ),
        Quote(
            "He who feeds you, controls you.",
            "Celui qui te nourrit, te contrôle.",
        ),
    )
}
