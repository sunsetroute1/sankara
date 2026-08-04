package com.joshuatz.nfceinkwriter.trailtag

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** tel: / sms: links for offline HTML safety pages. */
object TrailTagLinkBuilder {

    fun tel(phone: String): String = "tel:${sanitizePhone(phone)}"

    fun sms(phone: String, body: String = ""): String {
        val num = sanitizePhone(phone)
        if (body.isBlank()) return "sms:$num"
        val encoded = URLEncoder.encode(body, StandardCharsets.UTF_8.name())
        return "sms:$num?body=$encoded"
    }

    private fun sanitizePhone(phone: String): String =
        phone.trim().replace(Regex("[^+0-9]"), "")
}
