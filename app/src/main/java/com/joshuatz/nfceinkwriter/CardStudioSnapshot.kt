package com.joshuatz.nfceinkwriter

import org.json.JSONObject

/** Serializable Card Studio form state. */
data class CardStudioSnapshot(
    val cardType: String,
    val wifiSecurity: String,
    val fields: Map<String, String>,
) {
    fun displayLabel(): String {
        val primary = when (cardType) {
            TYPE_QR -> fields[KEY_QR_CONTENT].orEmpty()
            TYPE_WIFI -> fields[KEY_WIFI_SSID].orEmpty()
            TYPE_CONTACT -> fields[KEY_CONTACT_NAME].orEmpty()
            TYPE_LINKS -> fields[KEY_LINKS_HANDLE].orEmpty().ifBlank {
                fields[KEY_LINK_1].orEmpty()
            }
            else -> ""
        }.trim()
        if (primary.isBlank()) return typeLabel()
        val trimmed = if (primary.length > 28) primary.take(25) + "…" else primary
        return "${typeLabel()}: $trimmed"
    }

    fun typeLabel(): String = when (cardType) {
        TYPE_QR -> "QR"
        TYPE_WIFI -> "Wi‑Fi"
        TYPE_CONTACT -> "Contact"
        TYPE_LINKS -> "Links"
        else -> "Card"
    }

    fun hasContent(): Boolean = when (cardType) {
        TYPE_QR -> fields[KEY_QR_CONTENT].orEmpty().isNotBlank()
        TYPE_WIFI -> fields[KEY_WIFI_SSID].orEmpty().isNotBlank()
        TYPE_CONTACT -> fields[KEY_CONTACT_NAME].orEmpty().isNotBlank()
        TYPE_LINKS -> listOf(KEY_LINK_1, KEY_LINK_2, KEY_LINK_3, KEY_LINK_4).any {
            fields[it].orEmpty().isNotBlank()
        }
        else -> false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("cardType", cardType)
        put("wifiSecurity", wifiSecurity)
        put("fields", JSONObject(fields))
    }

    companion object {
        const val TYPE_QR = "QR"
        const val TYPE_WIFI = "WIFI"
        const val TYPE_CONTACT = "CONTACT"
        const val TYPE_LINKS = "LINKS"

        const val KEY_QR_CONTENT = "qr_content"
        const val KEY_QR_LABEL = "qr_label"
        const val KEY_WIFI_SSID = "wifi_ssid"
        const val KEY_WIFI_PASSWORD = "wifi_password"
        const val KEY_CONTACT_NAME = "contact_name"
        const val KEY_CONTACT_TITLE = "contact_title"
        const val KEY_CONTACT_PHONE = "contact_phone"
        const val KEY_CONTACT_EMAIL = "contact_email"
        const val KEY_CONTACT_WEBSITE = "contact_website"
        const val KEY_LINKS_HANDLE = "links_handle"
        const val KEY_LINK_1 = "link_1"
        const val KEY_LINK_2 = "link_2"
        const val KEY_LINK_3 = "link_3"
        const val KEY_LINK_4 = "link_4"

        fun fromJson(json: JSONObject): CardStudioSnapshot {
            val fieldsJson = json.optJSONObject("fields") ?: JSONObject()
            val fields = mutableMapOf<String, String>()
            fieldsJson.keys().forEach { key ->
                fields[key] = fieldsJson.optString(key, "")
            }
            return CardStudioSnapshot(
                cardType = json.optString("cardType", TYPE_QR),
                wifiSecurity = json.optString("wifiSecurity", "WPA"),
                fields = fields,
            )
        }
    }
}
