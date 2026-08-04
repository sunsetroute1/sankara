package com.joshuatz.nfceinkwriter

/** Solid or test frames sent instead of user artwork — recovery and diagnosis. */
enum class PanelTestPattern(val storageKey: String) {
    WHITE("white"),
    BLACK("black"),
    CHECKERBOARD("checkerboard"),
    HORIZONTAL_BARS("bars"),
    ;

    companion object {
        fun fromStorageKey(key: String?): PanelTestPattern? =
            entries.firstOrNull { it.storageKey == key }
    }
}
