package com.joshuatz.nfceinkwriter.trailtag

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Compact safety snapshot embedded in the QR URL — readable by any phone browser
 * via the static TrailTag viewer (no Sankara install, no server API).
 */
object TrailTagQrPayload {

    const val FORMAT_VERSION = 1
    private const val COMPRESS_THRESHOLD_BYTES = 280

    data class Snapshot(
        val profile: TrailTagProfile,
        val session: TrailTagSession?,
        val status: AdventureStatus,
    )

    fun encode(snapshot: Snapshot): String {
        val json = toCompactJson(snapshot)
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        return if (raw.size > COMPRESS_THRESHOLD_BYTES) {
            "z" + base64Url(gzip(raw))
        } else {
            "r" + base64Url(raw)
        }
    }

    fun decode(token: String): JSONObject? {
        if (token.length < 2) return null
        return try {
            val flag = token[0]
            val data = base64UrlDecode(token.substring(1))
            val jsonBytes = when (flag) {
                'z' -> gunzip(data)
                'r' -> data
                else -> return null
            }
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** Recommended max encoded token length for reliable e-ink QR (version ~25, EC-L). */
    const val MAX_QR_TOKEN_CHARS = 2_400

    fun encodedLength(snapshot: Snapshot): Int = encode(snapshot).length

    private fun toCompactJson(snapshot: Snapshot): JSONObject {
        val p = snapshot.profile
        val s = snapshot.session
        return JSONObject().apply {
            put("v", FORMAT_VERSION)
            put("n", p.personLabel())
            put("stat", snapshot.status.name.lowercase())
            s?.let { sess ->
                put("act", sess.activityType.label)
                if (sess.location.isNotBlank()) put("loc", sess.location)
                if (sess.route.isNotBlank()) put("rte", sess.route)
                put("st", sess.startTimeMs)
                put("ret", sess.expectedReturnMs)
                put("em", sess.emergencyThresholdMs)
                if (sess.notes.isNotBlank()) put("note", sess.notes.take(120))
            }
            val contacts = JSONArray()
            p.contacts.filter { it.isConfigured() }.take(3).forEach { c ->
                contacts.put(JSONObject().apply {
                    if (c.name.isNotBlank()) put("n", c.name)
                    if (c.primaryPhone.isNotBlank()) put("p", c.primaryPhone)
                    if (c.secondaryPhone.isNotBlank()) put("s", c.secondaryPhone)
                })
            }
            if (contacts.length() > 0) put("contacts", contacts)
            val track = JSONArray()
            p.trackingLinks.filter { it.url.isNotBlank() }.take(4).forEach { link ->
                track.put(JSONObject().apply {
                    put("l", link.label)
                    put("u", link.url)
                })
            }
            if (track.length() > 0) put("track", track)
            if (p.vehicle.hasContent()) {
                put("veh", JSONObject().apply {
                    if (p.vehicle.makeModel.isNotBlank()) put("m", p.vehicle.makeModel)
                    if (p.vehicle.color.isNotBlank()) put("c", p.vehicle.color)
                    if (p.vehicle.licensePlate.isNotBlank()) put("p", p.vehicle.licensePlate)
                })
            }
            if (p.medical.hasContent()) {
                put("med", JSONObject().apply {
                    if (p.medical.bloodType.isNotBlank()) put("b", p.medical.bloodType)
                    if (p.medical.allergies.isNotBlank()) put("a", p.medical.allergies.take(80))
                    if (p.medical.notes.isNotBlank()) put("n", p.medical.notes.take(80))
                })
            }
        }
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun base64UrlDecode(text: String): ByteArray {
        var padded = text.replace('-', '+').replace('_', '/')
        when (padded.length % 4) {
            2 -> padded += "=="
            3 -> padded += "="
        }
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(input.size)
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray {
        java.util.zip.GZIPInputStream(input.inputStream()).use { return it.readBytes() }
    }
}
