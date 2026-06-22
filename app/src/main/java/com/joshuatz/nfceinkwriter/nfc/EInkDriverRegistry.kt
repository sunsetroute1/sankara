package com.joshuatz.nfceinkwriter.nfc

import android.content.Context
import android.nfc.Tag
import com.joshuatz.nfceinkwriter.WaveShareUID
import com.joshuatz.nfceinkwriter.nfc.discovery.DiscoveryResult
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkDiscoveryEngine
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.IsoDepBadgeTagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.PassiveD0D1TagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.WaveshareTagProbe
import com.joshuatz.nfceinkwriter.nfc.passive.PassiveInkZoneDriver
import com.joshuatz.nfceinkwriter.nfc.waveshare.WaveshareNfcDriver
import java.nio.charset.StandardCharsets

fun tagHasIsoDep(tag: Tag): Boolean =
    tag.techList.any { it == android.nfc.tech.IsoDep::class.java.name }

fun tagHasNfcA(tag: Tag): Boolean =
    tag.techList.any { it == android.nfc.tech.NfcA::class.java.name }

fun isRev22WaveshareModule(tag: Tag): Boolean =
    tagHasNfcA(tag) && tagHasIsoDep(tag)

fun isWaveshareTag(tag: Tag, ndefPayloadHint: Boolean): Boolean {
    if (!tagHasNfcA(tag)) return false
    if (ndefPayloadHint) return true
    if (isRev22WaveshareModule(tag)) return true
    val uidAscii = try {
        String(tag.id, StandardCharsets.US_ASCII)
    } catch (_: Exception) {
        ""
    }
    return uidAscii == WaveShareUID
}

fun tagUidHex(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

object EInkDriverRegistry {
    private val drivers: List<EInkNfcDriver> = listOf(
        WaveshareNfcDriver(),
        PassiveInkZoneDriver(),
        IsoDepEInkDriver(),
    )

    private val driversById: Map<String, EInkNfcDriver> = drivers.associateBy { it.id }

    fun allDrivers(): List<EInkNfcDriver> = drivers

    fun driverById(id: String): EInkNfcDriver? = driversById[id]

    fun findDriver(tag: Tag, ndefPayloadHint: Boolean): EInkNfcDriver? =
        drivers.firstOrNull { it.canHandle(tag, ndefPayloadHint) }

    suspend fun discover(
        context: Context,
        tag: Tag,
        hints: TagHints,
        passiveOnly: Boolean = true,
    ): DiscoveryResult = EInkDiscoveryEngine.discover(context, tag, hints, passiveOnly)

    suspend fun resolveDriver(
        context: Context,
        tag: Tag,
        hints: TagHints,
        cachedProfile: EInkTagProfile? = null,
        passiveOnly: Boolean = true,
    ): Pair<EInkNfcDriver, EInkTagProfile?>? {
        cachedProfile?.let { profile ->
            driverById(profile.driverId)?.let { return it to profile }
        }

        val discovery = discover(context, tag, hints, passiveOnly)
        val best = discovery.best
        if (best != null) {
            driverById(best.profile.driverId)?.let { return it to best.profile }
        }

        findDriver(tag, hints.waveshareNdef)?.let { return it to null }
        return null
    }

    fun describeTag(tag: Tag): String {
        val tech = tag.techList.joinToString(", ") { it.substringAfterLast('.') }
        return "UID ${tagUidHex(tag)} · $tech"
    }
}
