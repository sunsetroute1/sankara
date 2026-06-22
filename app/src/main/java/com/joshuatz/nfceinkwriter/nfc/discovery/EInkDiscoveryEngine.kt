package com.joshuatz.nfceinkwriter.nfc.discovery

import android.content.Context
import android.nfc.Tag
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.IsoDepBadgeTagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.MifareClassicDiagnosticProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.PassiveD0D1TagProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.Rev22ApduDiagnosticProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.WaveshareTagProbe

object EInkDiscoveryEngine {

    fun probesFor(context: Context, passiveOnly: Boolean): List<EInkTagProbe> {
        val all = listOf(
            WaveshareTagProbe(),
            Rev22ApduDiagnosticProbe(),
            MifareClassicDiagnosticProbe(),
            PassiveD0D1TagProbe(context),
            IsoDepBadgeTagProbe(),
        )
        return if (passiveOnly) {
            all.filter { it.protocolFamily.powerModel == PowerModel.PASSIVE }
        } else {
            all
        }.sortedBy { it.priority }
    }

    suspend fun discover(
        context: Context,
        tag: Tag,
        hints: TagHints,
        passiveOnly: Boolean = true,
    ): DiscoveryResult {
        val attempts = mutableListOf<ProbeAttempt>()
        var best: ProbeResult? = null

        for (probe in probesFor(context, passiveOnly)) {
            val result = try {
                probe.probe(tag, hints)
            } catch (e: Exception) {
                attempts.add(
                    ProbeAttempt(
                        probeId = probe.id,
                        probeName = probe.displayName,
                        matched = false,
                        detail = e.message ?: "Probe error",
                    ),
                )
                null
            }

            if (result == null) {
                if (attempts.none { it.probeId == probe.id }) {
                    attempts.add(
                        ProbeAttempt(
                            probeId = probe.id,
                            probeName = probe.displayName,
                            matched = false,
                            detail = "No match",
                        ),
                    )
                }
                continue
            }

            attempts.add(
                ProbeAttempt(
                    probeId = probe.id,
                    probeName = probe.displayName,
                    matched = true,
                    confidence = result.profile.confidence,
                    detail = result.profile.displayName,
                    rawHex = result.rawHex,
                ),
            )

            if (best == null || result.profile.confidence.ordinal < best!!.profile.confidence.ordinal) {
                best = result
            }
        }

        return DiscoveryResult(best = best, attempts = attempts, passiveOnly = passiveOnly)
    }
}
