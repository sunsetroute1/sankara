package com.joshuatz.nfceinkwriter.nfc.waveshare

/** Dual-plane 1bpp payload for the Waveshare Rev2.2 IsoDep transfer path. */
data class Rev22EncodedImage(
    /** Black/white plane (field `b` in the official app). */
    val blackWhite: ByteArray,
    /** Second plane — red on tri-color panels; all zeros on B/W modules (field `c`). */
    val chroma: ByteArray,
) {
    init {
        require(blackWhite.size == chroma.size) {
            "Planes must match (${blackWhite.size} vs ${chroma.size})"
        }
    }
}
