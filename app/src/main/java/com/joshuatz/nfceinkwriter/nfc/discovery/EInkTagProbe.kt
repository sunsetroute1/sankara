package com.joshuatz.nfceinkwriter.nfc.discovery

import android.nfc.Tag

/**
 * Read-only tag identification step. Probes must not write image data.
 * Register new passive protocols here as the ecosystem grows.
 */
interface EInkTagProbe {
    val id: String
    val displayName: String
    /** Lower runs first. */
    val priority: Int
    val protocolFamily: ProtocolFamily

    /** @return match result or null when this probe does not apply */
    suspend fun probe(tag: Tag, hints: TagHints): ProbeResult?
}
