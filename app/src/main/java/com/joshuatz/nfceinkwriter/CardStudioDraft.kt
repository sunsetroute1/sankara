package com.joshuatz.nfceinkwriter

import android.content.Context
import org.json.JSONObject

/** Auto-saved Card Studio draft (local only — may include Wi‑Fi passwords). */
class CardStudioDraft(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CardStudioSnapshot? {
        val raw = prefs.getString(KEY_DRAFT, null) ?: return null
        return try {
            CardStudioSnapshot.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: CardStudioSnapshot) {
        prefs.edit().putString(KEY_DRAFT, snapshot.toJson().toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DRAFT).apply()
    }

    companion object {
        private const val PREFS_NAME = "card_studio_draft"
        private const val KEY_DRAFT = "draft_json"
    }
}
