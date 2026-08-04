package com.joshuatz.nfceinkwriter

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Applies the user-selected app theme before inflation. */
abstract class ThemedActivity : AppCompatActivity() {

    private var loadedThemeStyle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        loadedThemeStyle = Preferences(this).getAppThemeStyle().prefValue
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val current = Preferences(this).getAppThemeStyle().prefValue
        if (loadedThemeStyle != null && loadedThemeStyle != current) {
            recreate()
        }
    }
}
