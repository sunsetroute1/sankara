package com.joshuatz.nfceinkwriter

enum class NfcTransferPhase {
    NFC_UNAVAILABLE,
    NFC_DISABLED,
    LISTENING,
    DISCOVERING,
    TAG_SEEN,
    TAG_WRONG,
    TRANSFERRING,
    SUCCESS,
    FAILED,
}
