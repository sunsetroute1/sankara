package com.joshuatz.nfceinkwriter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.joshuatz.nfceinkwriter.Constants.PreferenceKeys
import com.joshuatz.nfceinkwriter.Constants.Preference_File_Key
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.ProfileSource
import org.json.JSONObject

class Preferences(private val context: Context) {

    fun getPreferences(): SharedPreferences =
        context.getSharedPreferences(Preference_File_Key, Context.MODE_PRIVATE)

    fun getScreenSize(): String =
        getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize) ?: DefaultScreenSize

    fun getScreenSizeEnum(): Int =
        ScreenSizes.indexOf(getScreenSize()) + 1

    fun getScreenSizePixels(): Pair<Int, Int> =
        ScreenSizesInPixels[getScreenSize()]!!

    fun setScreenSize(size: String) {
        getPreferences().edit().putString(PreferenceKeys.DisplaySize, size).apply()
    }

    fun getColorMode(): EInkColorMode =
        EInkColorMode.fromPref(getPreferences().getString(PreferenceKeys.ColorMode, EInkColorMode.DEFAULT.prefValue))

    fun setColorMode(mode: EInkColorMode) {
        getPreferences().edit().putString(PreferenceKeys.ColorMode, mode.prefValue).apply()
    }

    fun getNfcPromptEnableOnOpen(): Boolean =
        getPreferences().getBoolean(PreferenceKeys.NfcPromptEnableOnOpen, true)

    fun setNfcPromptEnableOnOpen(enabled: Boolean) {
        getPreferences().edit().putBoolean(PreferenceKeys.NfcPromptEnableOnOpen, enabled).apply()
    }

    fun getNfcPromptDisableOnClose(): Boolean =
        getPreferences().getBoolean(PreferenceKeys.NfcPromptDisableOnClose, false)

    fun setNfcPromptDisableOnClose(enabled: Boolean) {
        getPreferences().edit().putBoolean(PreferenceKeys.NfcPromptDisableOnClose, enabled).apply()
    }

    fun getDevicePassword(): String =
        getPreferences().getString(PreferenceKeys.DevicePassword, DefaultDevicePassword)
            ?: DefaultDevicePassword

    fun setDevicePassword(password: String) {
        getPreferences().edit().putString(PreferenceKeys.DevicePassword, password).apply()
    }

    fun getAppThemeStyle(): AppThemeStyle =
        AppThemeStyle.fromPref(getPreferences().getString(PreferenceKeys.AppThemeStyle, null))

    fun setAppThemeStyle(style: AppThemeStyle) {
        getPreferences().edit().putString(PreferenceKeys.AppThemeStyle, style.prefValue).apply()
    }

    fun showScreenSizePicker(activity: Activity, callback: (String) -> Unit) {
        AlertDialog.Builder(activity, AppTheme.applyDialogTheme(activity))
            .setTitle(R.string.settings_display_size)
            .setItems(ScreenSizes) { _, which ->
                val selected = ScreenSizes[which]
                setScreenSize(selected)
                callback(selected)
            }
            .show()
    }

    fun getCachedTagProfile(uidHex: String): EInkTagProfile? {
        val json = getPreferences().getString(profileKey(uidHex), null) ?: return null
        return try {
            EInkTagProfile.fromJson(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    fun cacheTagProfile(uidHex: String, profile: EInkTagProfile) {
        val cached = profile.copy(source = ProfileSource.CACHED)
        getPreferences().edit()
            .putString(profileKey(uidHex), cached.toJson().toString())
            .apply()
    }

    fun applyProfileAsDefaults(profile: EInkTagProfile) {
        profile.screenSizeKey?.let { setScreenSize(it) }
        profile.colorMode?.let { setColorMode(it) }
    }

    private fun profileKey(uidHex: String) = "${PreferenceKeys.TagProfiles}_$uidHex"
}
