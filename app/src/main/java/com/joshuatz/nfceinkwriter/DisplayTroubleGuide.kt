package com.joshuatz.nfceinkwriter

/** Maps visible panel symptoms to likely causes and recovery steps. */
object DisplayTroubleGuide {

    enum class Symptom(val storageKey: String) {
        PARTIAL_OVERLAY("partial"),
        HALF_UPDATED("half"),
        UNCHANGED("unchanged"),
        GHOST_IMAGE("ghost"),
        INVERTED("inverted"),
        BANDS("bands"),
        NOISE("noise"),
    }

    data class Diagnosis(
        val titleRes: Int,
        val causeRes: Int,
        val fixRes: Int,
        val recommendedPattern: PanelTestPattern,
    )

    fun diagnosisFor(symptom: Symptom): Diagnosis = when (symptom) {
        Symptom.PARTIAL_OVERLAY -> Diagnosis(
            R.string.troubleshoot_symptom_partial_title,
            R.string.troubleshoot_symptom_partial_cause,
            R.string.troubleshoot_symptom_partial_fix,
            PanelTestPattern.WHITE,
        )
        Symptom.HALF_UPDATED -> Diagnosis(
            R.string.troubleshoot_symptom_half_title,
            R.string.troubleshoot_symptom_half_cause,
            R.string.troubleshoot_symptom_half_fix,
            PanelTestPattern.CHECKERBOARD,
        )
        Symptom.UNCHANGED -> Diagnosis(
            R.string.troubleshoot_symptom_unchanged_title,
            R.string.troubleshoot_symptom_unchanged_cause,
            R.string.troubleshoot_symptom_unchanged_fix,
            PanelTestPattern.BLACK,
        )
        Symptom.GHOST_IMAGE -> Diagnosis(
            R.string.troubleshoot_symptom_ghost_title,
            R.string.troubleshoot_symptom_ghost_cause,
            R.string.troubleshoot_symptom_ghost_fix,
            PanelTestPattern.WHITE,
        )
        Symptom.INVERTED -> Diagnosis(
            R.string.troubleshoot_symptom_inverted_title,
            R.string.troubleshoot_symptom_inverted_cause,
            R.string.troubleshoot_symptom_inverted_fix,
            PanelTestPattern.CHECKERBOARD,
        )
        Symptom.BANDS -> Diagnosis(
            R.string.troubleshoot_symptom_bands_title,
            R.string.troubleshoot_symptom_bands_cause,
            R.string.troubleshoot_symptom_bands_fix,
            PanelTestPattern.HORIZONTAL_BARS,
        )
        Symptom.NOISE -> Diagnosis(
            R.string.troubleshoot_symptom_noise_title,
            R.string.troubleshoot_symptom_noise_cause,
            R.string.troubleshoot_symptom_noise_fix,
            PanelTestPattern.WHITE,
        )
    }

    val allSymptoms: List<Symptom> = Symptom.entries
}
