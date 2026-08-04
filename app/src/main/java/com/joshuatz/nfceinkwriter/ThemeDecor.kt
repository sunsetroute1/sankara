package com.joshuatz.nfceinkwriter

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.appbar.MaterialToolbar

/** Shows or hides Sankara-specific chrome when the modern theme is active. */
object ThemeDecor {

    fun applyMainScreen(activity: MainActivity) {
        val sankara = Preferences(activity).getAppThemeStyle().isSankara
        activity.findViewById<View>(R.id.panAfricanStripe)?.visibility =
            if (sankara) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.lastSyncPanStripe)?.visibility =
            if (sankara) View.VISIBLE else View.GONE
        activity.findViewById<MaterialToolbar>(R.id.main_toolbar)?.subtitle =
            if (sankara) activity.getString(R.string.app_tagline) else null
        listOf(R.id.statusSectionAccent, R.id.lastSyncSectionAccent, R.id.createSectionAccent)
            .forEach { id -> trimSectionAccent(activity.findViewById(id), sankara) }
    }

    fun applySettingsScreen(activity: SettingsActivity) {
        val sankara = Preferences(activity).getAppThemeStyle().isSankara
        activity.findViewById<View>(R.id.panAfricanStripe)?.visibility =
            if (sankara) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.quoteCard)?.visibility =
            if (sankara) View.VISIBLE else View.GONE
        activity.findViewById<ImageView>(R.id.settingsBackground)?.visibility =
            if (sankara) View.VISIBLE else View.GONE
    }

    private fun trimSectionAccent(section: View?, sankara: Boolean) {
        val group = section as? ViewGroup ?: return
        if (group.childCount >= 3) {
            group.getChildAt(1).visibility = if (sankara) View.VISIBLE else View.GONE
            group.getChildAt(2).visibility = if (sankara) View.VISIBLE else View.GONE
        }
    }
}
