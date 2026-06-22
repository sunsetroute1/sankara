package com.joshuatz.nfceinkwriter

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder

enum class NfcRadioState {
    UNAVAILABLE,
    DISABLED,
    ENABLED,
}

object NfcHelper {
    private const val TAG = "NfcHelper"
    private var enablePromptShownThisSession = false

    fun resetSessionPrompts() {
        enablePromptShownThisSession = false
    }

    fun getAdapter(context: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    fun getRadioState(context: Context): NfcRadioState {
        val adapter = getAdapter(context) ?: return NfcRadioState.UNAVAILABLE
        return if (adapter.isEnabled) NfcRadioState.ENABLED else NfcRadioState.DISABLED
    }

    fun isEnabled(context: Context): Boolean = getRadioState(context) == NfcRadioState.ENABLED

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NFC_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "NFC settings intent failed, falling back to wireless settings", e)
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    fun registerStateReceiver(context: Context, onStateChanged: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    onStateChanged()
                }
            }
        }
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    fun unregisterStateReceiver(context: Context, receiver: BroadcastReceiver?) {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "NFC receiver already unregistered")
            }
        }
    }

    /** Prompt to turn NFC on when the app opens (Android requires manual toggle in system settings). */
    fun promptEnableIfNeeded(activity: Activity, preferences: Preferences) {
        if (!preferences.getNfcPromptEnableOnOpen()) return
        if (enablePromptShownThisSession) return
        when (getRadioState(activity)) {
            NfcRadioState.UNAVAILABLE -> return
            NfcRadioState.ENABLED -> return
            NfcRadioState.DISABLED -> {
                enablePromptShownThisSession = true
                MaterialAlertDialogBuilder(activity, R.style.Theme_Sankara)
                    .setTitle(R.string.nfc_enable_dialog_title)
                    .setMessage(R.string.nfc_enable_dialog_message)
                    .setPositiveButton(R.string.nfc_open_settings) { _, _ -> openSettings(activity) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Remind user to disable NFC when leaving sync (cannot toggle programmatically on stock Android). */
    fun promptDisableIfNeeded(activity: Activity, preferences: Preferences) {
        if (!preferences.getNfcPromptDisableOnClose()) return
        if (!isEnabled(activity)) return
        MaterialAlertDialogBuilder(activity, R.style.Theme_Sankara)
            .setTitle(R.string.nfc_disable_dialog_title)
            .setMessage(R.string.nfc_disable_dialog_message)
            .setPositiveButton(R.string.nfc_open_settings) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.nfc_keep_on, null)
            .show()
    }
}
