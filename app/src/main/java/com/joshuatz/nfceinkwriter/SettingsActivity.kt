package com.joshuatz.nfceinkwriter

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferences: Preferences
    private var nfcStateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.settingsAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.settingsScroll))
        preferences = Preferences(this)

        findViewById<MaterialToolbar>(R.id.settings_toolbar).setNavigationOnClickListener {
            finish()
        }

        showRandomQuote()

        findViewById<MaterialButton>(R.id.btn_open_discovery).setOnClickListener {
            startActivity(Intent(this, DisplayDiscoveryActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_pick_display_size).apply {
            text = getString(R.string.settings_display_size) + ": " + preferences.getScreenSize()
            setOnClickListener {
                preferences.showScreenSizePicker(this@SettingsActivity) { selected ->
                    text = getString(R.string.settings_display_size) + ": $selected"
                }
            }
        }

        val colorGroup = findViewById<RadioGroup>(R.id.colorModeGroup)
        when (preferences.getColorMode()) {
            EInkColorMode.BLACK_WHITE -> findViewById<MaterialRadioButton>(R.id.radio_bw).isChecked = true
            EInkColorMode.THREE_COLOR -> findViewById<MaterialRadioButton>(R.id.radio_bwr).isChecked = true
            EInkColorMode.FOUR_COLOR -> findViewById<MaterialRadioButton>(R.id.radio_bwry).isChecked = true
        }

        colorGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radio_bwr -> EInkColorMode.THREE_COLOR
                R.id.radio_bwry -> EInkColorMode.FOUR_COLOR
                else -> EInkColorMode.BLACK_WHITE
            }
            preferences.setColorMode(mode)
        }

        findViewById<MaterialButton>(R.id.btn_notification_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btn_nfc_settings).setOnClickListener {
            NfcHelper.openSettings(this)
        }

        findViewById<TextInputEditText>(R.id.input_device_password).apply {
            setText(preferences.getDevicePassword())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val value = text?.toString()?.trim().orEmpty()
                    preferences.setDevicePassword(value.ifEmpty { DefaultDevicePassword })
                }
            }
        }

        findViewById<MaterialSwitch>(R.id.switchNfcEnableOnOpen).apply {
            isChecked = preferences.getNfcPromptEnableOnOpen()
            setOnCheckedChangeListener { _, checked ->
                preferences.setNfcPromptEnableOnOpen(checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchNfcDisableOnClose).apply {
            isChecked = preferences.getNfcPromptDisableOnClose()
            setOnCheckedChangeListener { _, checked ->
                preferences.setNfcPromptDisableOnClose(checked)
            }
        }
    }

    override fun onPause() {
        findViewById<TextInputEditText>(R.id.input_device_password)?.let { input ->
            val value = input.text?.toString()?.trim().orEmpty()
            preferences.setDevicePassword(value.ifEmpty { DefaultDevicePassword })
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        nfcStateReceiver = NfcHelper.registerStateReceiver(this) {
            updateNfcStatus()
        }
    }

    override fun onStop() {
        NfcHelper.unregisterStateReceiver(this, nfcStateReceiver)
        nfcStateReceiver = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationStatus()
        updateNfcStatus()
    }

    private fun showRandomQuote() {
        val quote = SankaraQuotes.randomQuote(this)
        findViewById<TextView>(R.id.sankaraQuoteText).text = "\u201C${quote.english}\u201D"
    }

    private fun updateNotificationStatus() {
        val enabled = isNotificationListenerEnabled()
        findViewById<TextView>(R.id.notificationStatus).text = getString(
            if (enabled) R.string.status_access_granted else R.string.status_access_denied,
        )
    }

    private fun updateNfcStatus() {
        val label = when (NfcHelper.getRadioState(this)) {
            NfcRadioState.ENABLED -> getString(R.string.status_nfc_enabled)
            NfcRadioState.DISABLED -> getString(R.string.status_nfc_disabled)
            NfcRadioState.UNAVAILABLE -> getString(R.string.status_nfc_unavailable)
        }
        findViewById<TextView>(R.id.nfcStatus).text = getString(R.string.settings_nfc_status, label)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, MediaNotificationListener::class.java)
        return flat.split(":").any { TextUtils.equals(it, cn.flattenToString()) }
    }
}
