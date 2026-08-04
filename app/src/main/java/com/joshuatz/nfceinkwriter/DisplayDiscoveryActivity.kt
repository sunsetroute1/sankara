package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.PatternMatcher
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.joshuatz.nfceinkwriter.nfc.EInkDriverRegistry
import com.joshuatz.nfceinkwriter.nfc.discovery.DiscoveryResult
import com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.MifareClassicDiagnosticProbe
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.Rev22ApduDiagnosticProbe
import com.joshuatz.nfceinkwriter.nfc.tagUidHex
import kotlinx.coroutines.launch

/**
 * On-device passive e-paper discovery. Probes known protocols without flashing image data.
 * New passive drivers register probes in [com.joshuatz.nfceinkwriter.nfc.discovery.EInkDiscoveryEngine].
 */
class DisplayDiscoveryActivity : ThemedActivity() {

    private lateinit var preferences: Preferences
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists = arrayOf(
        arrayOf(NfcA::class.java.name),
        arrayOf(IsoDep::class.java.name),
        arrayOf(NfcA::class.java.name, IsoDep::class.java.name),
    )
    private var nfcStateReceiver: BroadcastReceiver? = null
    private var discoverArmed = false
    private var lastProfile: EInkTagProfile? = null
    private var lastUidHex: String? = null
    private var readerModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_discovery)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.discoveryAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.discoveryScroll))
        preferences = Preferences(this)

        findViewById<MaterialToolbar>(R.id.discovery_toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnStartDiscovery).setOnClickListener { armDiscovery() }
        findViewById<MaterialButton>(R.id.btnApplyDiscovery).setOnClickListener { applyLastProfile() }

        setupNfcDispatch()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        updateStatus(getString(R.string.discovery_status_idle), getString(R.string.discovery_status_idle_detail))
    }

    override fun onStart() {
        super.onStart()
        nfcStateReceiver = NfcHelper.registerStateReceiver(this) { refreshNfcState() }
    }

    override fun onStop() {
        NfcHelper.unregisterStateReceiver(this, nfcStateReceiver)
        nfcStateReceiver = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        NfcHelper.promptEnableIfNeeded(this, preferences)
        refreshNfcState()
        if (discoverArmed && NfcHelper.isEnabled(this)) {
            enableReaderMode()
        }
    }

    override fun onPause() {
        super.onPause()
        discoverArmed = false
        disableNfcListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isNfcIntent(intent)) {
            handleNfcIntent(intent)
        }
    }

    private fun armDiscovery() {
        if (!NfcHelper.isEnabled(this)) {
            updateStatus(
                getString(R.string.discovery_status_nfc_off),
                getString(R.string.discovery_status_nfc_off_detail),
            )
            return
        }
        discoverArmed = true
        lastProfile = null
        lastUidHex = null
        findViewById<View>(R.id.discoveryResultCard).visibility = View.GONE
        updateStatus(
            getString(R.string.discovery_status_listening),
            getString(R.string.discovery_status_listening_detail),
        )
        findViewById<TextView>(R.id.discoveryProbeLog).text = ""
        enableReaderMode()
    }

    private fun handleNfcIntent(intent: Intent) {
        if (!discoverArmed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val uidHex = tagUidHex(tag)
        val techSummary = EInkDriverRegistry.describeTag(tag)
        var waveshareNdef = false
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.forEach { msg ->
                (msg as NdefMessage).records.forEach { record ->
                    if (String(record.payload).contains("waveshare.feng.nfctag")) {
                        waveshareNdef = true
                    }
                }
            }
        }

        discoverArmed = false
        disableNfcListening()
        updateStatus(getString(R.string.discovery_status_probing), techSummary)

        val hints = TagHints(
            waveshareNdef = waveshareNdef,
            suggestedScreenSizeKey = preferences.getScreenSize(),
            suggestedWaveshareSdkType = preferences.getScreenSizeEnum(),
        )

        lifecycleScope.launch {
            val result = EInkDriverRegistry.discover(this@DisplayDiscoveryActivity, tag, hints, passiveOnly = true)
            showDiscoveryResult(uidHex, techSummary, result)
        }
    }

    private fun handleReaderTag(tag: Tag) {
        if (!discoverArmed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return

        val uidHex = tagUidHex(tag)
        val techSummary = EInkDriverRegistry.describeTag(tag)
        discoverArmed = false
        updateStatus(getString(R.string.discovery_status_probing), techSummary)

        val hints = TagHints(
            waveshareNdef = false,
            suggestedScreenSizeKey = preferences.getScreenSize(),
            suggestedWaveshareSdkType = preferences.getScreenSizeEnum(),
        )

        lifecycleScope.launch {
            val result = EInkDriverRegistry.discover(this@DisplayDiscoveryActivity, tag, hints, passiveOnly = true)
            showDiscoveryResult(uidHex, techSummary, result)
            disableNfcListening()
        }
    }

    private fun showDiscoveryResult(uidHex: String, techSummary: String, result: DiscoveryResult) {
        val log = buildString {
            result.attempts.forEach { attempt ->
                append(if (attempt.matched) "✓ " else "✗ ")
                append(attempt.probeName)
                append(": ")
                append(attempt.detail)
                if (!attempt.rawHex.isNullOrBlank()) {
                    append("\n  ")
                    append(attempt.rawHex)
                }
                append('\n')
            }
        }
        findViewById<TextView>(R.id.discoveryProbeLog).text = log.trim()

        val best = result.best
        if (best == null) {
            updateStatus(
                getString(R.string.discovery_status_unknown),
                getString(R.string.discovery_status_unknown_detail, techSummary),
            )
            findViewById<View>(R.id.discoveryResultCard).visibility = View.GONE
            return
        }

        lastProfile = best.profile
        lastUidHex = uidHex
        val diagnosticOnly = isDiagnosticProfile(best.profile)
        if (!diagnosticOnly) {
            preferences.cacheTagProfile(uidHex, best.profile)
        }

        val profile = best.profile
        val detail = buildString {
            append("${profile.width}×${profile.height}")
            append(" · ")
            append(profile.protocolFamily.displayName)
            append(" · ")
            append(profile.confidence.name.lowercase().replaceFirstChar { it.titlecase() })
            profile.hardwareCode?.let { append("\nhw=0x${it.toString(16)}") }
            profile.notes?.let { append("\n$it") }
        }

        findViewById<TextView>(R.id.discoveryResultName).text = profile.displayName
        findViewById<TextView>(R.id.discoveryResultDetail).text = detail
        findViewById<View>(R.id.discoveryResultCard).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.btnApplyDiscovery).visibility =
            if (diagnosticOnly) View.GONE else View.VISIBLE
        updateStatus(getString(R.string.discovery_status_found), techSummary)
    }

    private fun applyLastProfile() {
        val profile = lastProfile ?: return
        if (isDiagnosticProfile(profile)) return
        val uid = lastUidHex
        preferences.applyProfileAsDefaults(profile)
        if (uid != null) {
            preferences.cacheTagProfile(uid, profile)
        }
        updateStatus(
            getString(R.string.discovery_status_applied),
            getString(
                R.string.discovery_status_applied_detail,
                profile.displayName,
                profile.width,
                profile.height,
            ),
        )
    }

    private fun updateStatus(title: String, detail: String) {
        findViewById<TextView>(R.id.discoveryStatusTitle).text = title
        findViewById<TextView>(R.id.discoveryStatusDetail).text = detail
    }

    private fun refreshNfcState() {
        if (discoverArmed) return
        when (NfcHelper.getRadioState(this)) {
            NfcRadioState.UNAVAILABLE -> updateStatus(
                getString(R.string.discovery_status_no_nfc),
                getString(R.string.discovery_status_no_nfc_detail),
            )
            NfcRadioState.DISABLED -> updateStatus(
                getString(R.string.discovery_status_nfc_off),
                getString(R.string.discovery_status_nfc_off_detail),
            )
            NfcRadioState.ENABLED -> Unit
        }
    }

    private fun setupNfcDispatch() {
        val nfcIntent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        pendingIntent = PendingIntent.getActivity(this, 1, nfcIntent, flags)

        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefFilter.addDataAuthority("ext", null)
            ndefFilter.addDataPath(".*", PatternMatcher.PATTERN_SIMPLE_GLOB)
            ndefFilter.addDataScheme("vnd.android.nfc")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e(TAG, "Invalid NDEF filter", e)
        }
        intentFilters = arrayOf(
            ndefFilter,
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        )
    }

    private fun enableForegroundDispatch() {
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }

    private fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun enableReaderMode() {
        if (readerModeActive) return
        val adapter = nfcAdapter ?: return
        try {
            adapter.enableReaderMode(
                this,
                { tag -> runOnUiThread { handleReaderTag(tag) } },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
            readerModeActive = true
        } catch (e: Exception) {
            Log.e(TAG, "enableReaderMode failed", e)
            enableForegroundDispatch()
        }
    }

    private fun disableReaderMode() {
        if (!readerModeActive) return
        try {
            nfcAdapter?.disableReaderMode(this)
        } catch (e: Exception) {
            Log.w(TAG, "disableReaderMode failed", e)
        }
        readerModeActive = false
    }

    private fun disableNfcListening() {
        disableForegroundDispatch()
        disableReaderMode()
    }

    private fun isDiagnosticProfile(profile: EInkTagProfile): Boolean =
        profile.driverId == Rev22ApduDiagnosticProbe.DRIVER_ID ||
            profile.driverId == MifareClassicDiagnosticProbe.DRIVER_ID

    private fun isNfcIntent(intent: Intent): Boolean =
        intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED

    companion object {
        private const val TAG = "DisplayDiscovery"
    }
}
