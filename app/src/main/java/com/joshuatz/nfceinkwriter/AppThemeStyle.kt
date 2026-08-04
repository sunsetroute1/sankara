package com.joshuatz.nfceinkwriter

enum class AppThemeStyle(val prefValue: String, val styleRes: Int, val cropStyleRes: Int) {
    SANKARA("sankara", R.style.Theme_Sankara, R.style.Theme_Sankara_Crop),
    MODERN("modern", R.style.Theme_Modern, R.style.Theme_Modern_Crop),
    ;

    val isSankara: Boolean get() = this == SANKARA

    companion object {
        fun fromPref(value: String?): AppThemeStyle =
            entries.firstOrNull { it.prefValue == value } ?: SANKARA
    }
}

object AppTheme {

    fun apply(activity: android.app.Activity) {
        val prefs = Preferences(activity)
        activity.setTheme(prefs.getAppThemeStyle().styleRes)
    }

    fun applyDialogTheme(activity: android.app.Activity): Int {
        val prefs = Preferences(activity)
        return if (prefs.getAppThemeStyle().isSankara) {
            R.style.Theme_Sankara
        } else {
            R.style.Theme_Modern
        }
    }
}
