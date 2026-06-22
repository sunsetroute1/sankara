package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.joshuatz.nfceinkwriter.nfc.EInkDriverRegistry
import com.joshuatz.nfceinkwriter.nfc.NfcFlashSession
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.WaveshareTagProbe
import com.joshuatz.nfceinkwriter.nfc.isWaveshareTag
import com.joshuatz.nfceinkwriter.nfc.tagUidHex
import com.joshuatz.nfceinkwriter.nfc.EInkFlashResult
import com.joshuatz.nfceinkwriter.nfc.waveshare.Rev22WaveshareDriver
import com.joshuatz.nfceinkwriter.nfc.waveshare.OfficialWaveshareDriver
import com.joshuatz.nfceinkwriter.nfc.waveshare.WaveshareBitmapPrep
import com.joshuatz.nfceinkwriter.nfc.waveshare.WavesharePanel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NfcFlasher : AppCompatActivity() {
    private var mIsFlashing = false
        set(isFlashing) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                runOnUiThread { mIsFlashing = isFlashing }
                return
            }
            field = isFlashing
            whileFlashingArea?.visibility = if (isFlashing) View.VISIBLE else View.GONE
            whileFlashingArea?.requestLayout()
            mProgressVal = 0
            if (!isFlashing) {
                progressPercentView?.text = "0%"
            }
        }

    private var mNfcAdapter: NfcAdapter? = null
    private var mPendingIntent: PendingIntent? = null
    private var mNfcIntentFilters: Array<IntentFilter>? = null
    private var mNfcTechList = arrayOf(
        arrayOf(NfcA::class.java.name),
        arrayOf(android.nfc.tech.IsoDep::class.java.name),
        arrayOf(NfcA::class.java.name, android.nfc.tech.IsoDep::class.java.name),
    )
    private var mNfcCheckHandler: Handler? = null
    private val mNfcCheckIntervalMs = 250L
    private var mProgressBar: ProgressBar? = null
    private var mProgressVal: Int = 0
    private var mBitmap: Bitmap? = null
    private var whileFlashingArea: View? = null
    private var mImgFilePath: String? = null
    private var mImgFileUri: Uri? = null
    private lateinit var preferences: Preferences
    private var nfcStateReceiver: BroadcastReceiver? = null
    private var currentPhase = NfcTransferPhase.LISTENING

    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var statusDot: View? = null
    private var btnOpenNfcSettings: MaterialButton? = null
    private var postTransferActions: View? = null
    private var progressPercentView: TextView? = null
    private var btnStartSync: MaterialButton? = null
    /** True only after user taps Start sync — transfer is gated on this. */
    private var syncArmed = false
    private var lastDetectedUid: String? = null
    private var lastDetectedSummary: String? = null
    private var lastReaderHintUid: String? = null
    private var lastReaderHintAtMs: Long = 0L
    private var readerModeActive = false
    private var foregroundDispatchActive = false
    private val transferPending = AtomicBoolean(false)
    private var preparedPayloadBitmap: Bitmap? = null
    private var transferStartedAtMs: Long = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    /**
     * After a successful sync the module may still be refreshing — rapid re-sync often fails.
     * Persisted because the flasher activity is recreated between syncs (Card Studio → flasher,
     * reflash, etc.), which used to silently reset the cooldown.
     */
    private val syncStatePrefs by lazy { getSharedPreferences("nfc_sync_state", MODE_PRIVATE) }
    private var lastSuccessfulSyncAtMs: Long
        get() = syncStatePrefs.getLong(KEY_LAST_SUCCESS_MS, 0L)
        set(value) {
            syncStatePrefs.edit().putLong(KEY_LAST_SUCCESS_MS, value).apply()
        }
    private var lastReportedProgress = 0
    /** Automatic fresh-handle retries after transient failures (reset on success/manual arm). */
    private var autoRearmCount = 0
    /** Next armed sync sends an all-white frame instead of the preview image (recovery). */
    private var clearPanelMode = false
    private var userCancelledTransfer = false
    private var preferRev22Transfer: Boolean
        get() = syncStatePrefs.getBoolean(KEY_PREFER_REV22, false)
        set(value) {
            syncStatePrefs.edit().putBoolean(KEY_PREFER_REV22, value).apply()
        }
    private val progressHeartbeat = object : Runnable {
        override fun run() {
            if (!mIsFlashing) return
            val elapsedMs = System.currentTimeMillis() - transferStartedAtMs
            val detail = transferProgressDetail(lastReportedProgress, elapsedMs)
            setTransferPhase(NfcTransferPhase.TRANSFERRING, detail)
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val mNfcCheckCallback: Runnable = object : Runnable {
        override fun run() {
            checkNfcAndAttemptRecover()
            mNfcCheckHandler?.postDelayed(this, mNfcCheckIntervalMs)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("syncArmed", syncArmed)
        if (mImgFileUri != null) {
            outState.putString("serializedGeneratedImgUri", mImgFileUri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.nfcAppBar))
        preferences = Preferences(this)
        syncArmed = savedInstanceState?.getBoolean("syncArmed") ?: false
        if (preferRev22Transfer) {
            Log.i(TAG, "Clearing prefer_rev22 — using official engine only")
            preferRev22Transfer = false
        }

        bindStatusViews()
        findViewById<MaterialToolbar>(R.id.nfc_toolbar).setNavigationOnClickListener {
            NfcHelper.promptDisableIfNeeded(this, preferences)
            finish()
        }
        btnOpenNfcSettings?.setOnClickListener { NfcHelper.openSettings(this) }
        findViewById<MaterialButton>(R.id.btnRetrySync).setOnClickListener {
            postTransferActions?.visibility = View.GONE
            clearPanelMode = false
            // User explicitly asked to sync again — never block behind post-success cooldown.
            armSync(bypassCooldown = true)
        }
        findViewById<MaterialButton>(R.id.btnClearPanel).setOnClickListener {
            postTransferActions?.visibility = View.GONE
            clearPanelMode = true
            Log.i(TAG, "Clear panel tapped — next sync sends all-white frame")
            armSync(bypassCooldown = true)
        }
        btnStartSync = findViewById(R.id.btnStartSync)
        btnStartSync?.setOnClickListener {
            Log.i(TAG, "Start sync tapped")
            clearPanelMode = false
            armSync()
        }
        findViewById<MaterialButton>(R.id.btnDoneSync).setOnClickListener {
            NfcHelper.promptDisableIfNeeded(this, preferences)
            finish()
        }

        val savedUriStr = savedInstanceState?.getString("serializedGeneratedImgUri")
        if (savedUriStr != null) {
            mImgFileUri = Uri.parse(savedUriStr)
        } else {
            mImgFilePath = intent.extras?.getString(IntentKeys.GeneratedImgPath)
            if (mImgFilePath != null) {
                mImgFileUri = Uri.fromFile(getFileStreamPath(mImgFilePath))
            }
        }
        if (mImgFileUri == null) {
            mImgFileUri = Uri.fromFile(getFileStreamPath(GeneratedImageFilename))
        }

        mImgFileUri?.path?.let { path ->
            mBitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options())
        }

        findViewById<ImageView>(R.id.previewImageView).let { preview ->
            mBitmap?.let { bmp ->
                val pixels = preferences.getScreenSizePixels()
                PanelPreview.bind(preview, bmp, pixels.first, pixels.second)
            } ?: run {
                preview.setImageURI(mImgFileUri)
            }
        }

        whileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        progressPercentView = findViewById(R.id.transferProgressPercent)
        postTransferActions = findViewById(R.id.postTransferActions)
        findViewById<MaterialButton>(R.id.btnCancelSync).setOnClickListener { cancelActiveTransfer() }

        setupNfcDispatch()
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        startNfcCheckLoop()
        refreshNfcRadioStatus()
        updateSyncArmedUi()
        if (isNfcTagIntent(intent)) {
            handleNfcIntent(intent)
        }
    }

    private fun bindStatusViews() {
        statusTitle = findViewById(R.id.nfcStatusTitle)
        statusDetail = findViewById(R.id.nfcStatusDetail)
        statusDot = findViewById(R.id.nfcStatusDot)
        btnOpenNfcSettings = findViewById(R.id.btnOpenNfcSettings)
    }

    private fun setupNfcDispatch() {
        val nfcIntent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        var pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags = pendingFlags or PendingIntent.FLAG_MUTABLE
        }
        mPendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, pendingFlags)

        val ndefIntentFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefIntentFilter.addDataAuthority("ext", null)
            ndefIntentFilter.addDataPath(".*", PatternMatcher.PATTERN_SIMPLE_GLOB)
            ndefIntentFilter.addDataScheme("vnd.android.nfc")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e(TAG, "Invalid NDEF intent filter", e)
        }

        mNfcIntentFilters = arrayOf(
            ndefIntentFilter,
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        )
    }

    override fun onStart() {
        super.onStart()
        nfcStateReceiver = NfcHelper.registerStateReceiver(this) {
            refreshNfcRadioStatus()
        }
    }

    override fun onStop() {
        NfcHelper.unregisterStateReceiver(this, nfcStateReceiver)
        nfcStateReceiver = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        stopNfcCheckLoop()
        if (!mIsFlashing && !transferPending.get()) {
            disableNfcListening()
        }
        if (!mIsFlashing && !syncArmed) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        NfcHelper.promptEnableIfNeeded(this, preferences)
        startNfcCheckLoop()
        updateNfcListening()
        refreshNfcRadioStatus()
        updateSyncArmedUi()
        if (syncArmed && !mIsFlashing) {
            setTransferPhase(NfcTransferPhase.LISTENING, getString(R.string.hold_phone_to_flash_text))
        } else if (lastDetectedSummary != null && !mIsFlashing) {
            setTransferPhase(
                NfcTransferPhase.TAG_SEEN,
                getString(R.string.nfc_status_module_waiting, lastDetectedSummary!!),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isNfcTagIntent(intent)) {
            handleNfcIntent(intent)
        }
    }

    private fun isNfcTagIntent(intent: Intent): Boolean {
        return intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED
    }

    private fun handleNfcIntent(intent: Intent) {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val waveshareAar = waveshareAarFromIntent(intent)
        Log.i(TAG, "NFC intent: action=${intent.action}, armed=$syncArmed")
        if (!syncArmed) {
            onReaderTagDiscovered(tag, waveshareAar)
            return
        }
        // Armed transfers run from reader mode — FG-dispatch Tag handles go stale on Samsung.
        Log.d(TAG, "NFC intent while armed — reader mode handles transfer")
    }

    /** Foreground-dispatch path — transceive immediately; no UI work before the first frame. */
    private fun runOfficialTransferFromNfcIntent(tag: Tag) {
        if (!transferPending.compareAndSet(false, true)) return

        Log.i(
            TAG,
            "Official Waveshare transfer via foreground dispatch · ${EInkDriverRegistry.describeTag(tag)} " +
                "thread=${Thread.currentThread().name}",
        )

        val result = try {
            performOfficialTransferSync(tag) { progress ->
                showTransferProgress(progress)
            }.also {
                Log.i(TAG, "Official Waveshare result success=${it.success} message=${it.message}")
            }
        } catch (e: TagLostException) {
            Log.w(TAG, "Tag lost during official transfer", e)
            EInkFlashResult(
                false,
                "Transfer interrupted — partial image on panel. " +
                    "Tap Clear panel, hold still until done, wait 90s, then sync your image.",
                "Waveshare official",
                retryable = false,
                suppressAutoRearm = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Official transfer failed", e)
            EInkFlashResult(false, e.message ?: "Transfer failed", "Waveshare official")
        } finally {
            transferPending.set(false)
        }

        finishTransfer(result)
    }

    private fun showTransferProgress(progress: Int) {
        val clamped = progress.coerceIn(0, 100)
        uiHandler.post {
            if (!mIsFlashing) {
                mIsFlashing = true
                transferStartedAtMs = System.currentTimeMillis()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                uiHandler.removeCallbacks(progressHeartbeat)
                uiHandler.postDelayed(progressHeartbeat, 1_000L)
            }
            lastReportedProgress = clamped
            val elapsedMs = System.currentTimeMillis() - transferStartedAtMs
            val detail = when {
                clamped >= 100 -> getString(R.string.nfc_status_success)
                else -> transferProgressDetail(clamped, elapsedMs)
            }
            setTransferPhase(NfcTransferPhase.TRANSFERRING, detail)
            updateProgressBar(clamped)
        }
    }

    /** Upload is usually ~6s; only warn about long repaints once progress reaches the refresh phase. */
    private fun transferProgressDetail(progress: Int, elapsedMs: Long): String {
        val elapsed = formatElapsed(elapsedMs)
        val holdStill = getString(R.string.nfc_status_hold_still)
        return when {
            progress >= 90 && elapsedMs > 20_000L ->
                getString(R.string.nfc_status_refreshing_slow, elapsed) + "\n" + holdStill
            progress >= 90 ->
                getString(R.string.nfc_status_refreshing_elapsed, elapsed) + "\n" + holdStill
            elapsedMs > 4_000L ->
                getString(R.string.nfc_status_uploading_elapsed, elapsed) + "\n" + holdStill
            else -> getString(R.string.nfc_status_transferring)
        }
    }

    private fun stopProgressHeartbeat() {
        uiHandler.removeCallbacks(progressHeartbeat)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSec = (elapsedMs / 1_000L).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "%d:%02d".format(min, sec) else "0:%02d".format(sec)
    }

    /** Reader-mode callback — detection when idle; transfer when armed (fresh tag handle). */
    private fun onReaderTagDiscovered(tag: Tag, waveshareAar: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (transferPending.get()) return

        if (!syncArmed) {
            val uid = tagUidHex(tag)
            val now = System.currentTimeMillis()
            if (uid == lastReaderHintUid && now - lastReaderHintAtMs < READER_HINT_DEBOUNCE_MS) {
                return
            }
            lastReaderHintUid = uid
            lastReaderHintAtMs = now
            runOnUiThread {
                if (!mIsFlashing) {
                    showTagDetected(tag)
                    Log.d(TAG, "Detection only — tap Start sync to transfer")
                }
            }
            return
        }

        if (mIsFlashing) return
        if (!isWaveshareTag(tag, waveshareAar)) {
            runOnUiThread { showTagDetected(tag) }
            return
        }
        if (!transferPending.compareAndSet(false, true)) return

        Log.i(
            TAG,
            "Official Waveshare transfer on reader thread · ${EInkDriverRegistry.describeTag(tag)} " +
                "thread=${Thread.currentThread().name}",
        )

        val result = try {
            performOfficialTransferSync(tag) { progress ->
                showTransferProgress(progress)
            }.also {
                Log.i(TAG, "Official Waveshare result success=${it.success} message=${it.message}")
            }
        } catch (e: TagLostException) {
            Log.w(TAG, "Tag lost during official transfer", e)
            EInkFlashResult(
                false,
                "Transfer interrupted — partial image on panel. " +
                    "Tap Clear panel, hold still until done, wait 90s, then sync your image.",
                "Waveshare official",
                retryable = false,
                suppressAutoRearm = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Official transfer failed", e)
            EInkFlashResult(false, e.message ?: "Transfer failed", "Waveshare official")
        } finally {
            transferPending.set(false)
        }

        uiHandler.post { finishTransfer(result) }
    }

    private fun waveshareAarFromIntent(intent: Intent): Boolean {
        if (intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return false
        intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.forEach { msg ->
            val ndefMessage = msg as NdefMessage
            ndefMessage.records.forEach { record ->
                if (String(record.payload).contains("waveshare.feng.nfctag")) {
                    return true
                }
            }
        }
        return false
    }

    /** Detection only — updates UI, never starts transfer. */
    private fun showTagDetected(tag: Tag) {
        val techSummary = EInkDriverRegistry.describeTag(tag)
        lastDetectedUid = tagUidHex(tag)
        lastDetectedSummary = techSummary
        Log.i(TAG, "Module detected · armed=$syncArmed · $techSummary")
        setTransferPhase(
            NfcTransferPhase.TAG_SEEN,
            if (syncArmed) {
                getString(R.string.nfc_status_driver_detected, describeDriverHint(tag), techSummary)
            } else {
                getString(R.string.nfc_status_module_waiting, techSummary)
            },
        )
    }

    private fun describeDriverHint(tag: Tag): String {
        return when {
            isWaveshareTag(tag, false) -> "Waveshare"
            else -> "e-ink module"
        }
    }

    private fun onTagDetected(tag: Tag, waveshareAar: Boolean) {
        if (mIsFlashing) return
        if (!syncArmed) {
            showTagDetected(tag)
            Log.d(TAG, "Detection only — tap Start sync to transfer")
            return
        }
        // Armed transfers run synchronously from the reader callback — not here.
        showTagDetected(tag)
    }

    /** Bundled official Waveshare engine — run synchronously on the reader callback thread. */
    private fun performOfficialTransferSync(tag: Tag, progress: (Int) -> Unit): EInkFlashResult {
        val sdkType = WavesharePanel.sdkTypeForPreferences(preferences)
        val expected = WavesharePanel.expectedPixels(sdkType)
        val payload = if (clearPanelMode) {
            Log.i(TAG, "Clear-panel sync — sending ${expected.first}x${expected.second} all-white frame")
            WaveshareBitmapPrep.blankPanel(expected.first, expected.second)
        } else {
            prepareTransferPayload()
            val source = preparedPayloadBitmap ?: mBitmap
                ?: return EInkFlashResult(false, "missing image", "Waveshare official")
            preparedPayloadBitmap
                ?: WaveshareBitmapPrep.prepareForOfficial(source, expected.first, expected.second)
        }
        Log.i(
            TAG,
            "Official sync transfer sdkType=$sdkType panel=${expected.first}x${expected.second} " +
                "payload=${payload.width}x${payload.height} clearPanel=$clearPanelMode " +
                "preferRev22=$preferRev22Transfer",
        )

        val session = NfcFlashSession(
            context = this,
            screenSizeEnum = preferences.getScreenSizeEnum(),
            colorMode = preferences.getColorMode(),
            profile = WavesharePanel.transferProfile(preferences),
            devicePassword = preferences.getDevicePassword(),
        )

        if (preferRev22Transfer) {
            Log.i(TAG, "Trying Rev2.2 IsoDep protocol (alternate — experimental)")
            val rev22 = Rev22WaveshareDriver.transfer(tag, payload, expected, session, progress)
            preferRev22Transfer = false
            return rev22
        }

        val official = OfficialWaveshareDriver.transferSync(
            context = this,
            tag = tag,
            bitmap = payload,
            panelType = sdkType,
            password = preferences.getDevicePassword(),
            progress = progress,
            onRetry = { attempt, maxAttempts ->
                uiHandler.post {
                    setTransferPhase(
                        NfcTransferPhase.TRANSFERRING,
                        getString(R.string.nfc_status_retrying, attempt, maxAttempts),
                    )
                }
            },
        )
        if (official.refreshStalled) {
            // Do not auto-switch to Rev22 — chaining protocols corrupts the panel.
            Log.w(TAG, "Official refresh stalled at ${official.message}")
        } else if (official.success) {
            preferRev22Transfer = false
        }
        return official
    }

    private fun cancelActiveTransfer() {
        if (!mIsFlashing && !transferPending.get()) return
        Log.i(TAG, "User cancelled sync")
        userCancelledTransfer = true
        OfficialWaveshareDriver.abortActiveTransfer()
        setTransferPhase(NfcTransferPhase.TRANSFERRING, getString(R.string.nfc_status_cancelling))
    }

    private fun failTransfer(message: String) {
        mIsFlashing = false
        setTransferPhase(NfcTransferPhase.FAILED, message)
        showPostTransferActions()
    }

    private fun finishTransfer(result: com.joshuatz.nfceinkwriter.nfc.EInkFlashResult) {
        stopProgressHeartbeat()
        disableNfcListening()
        mIsFlashing = false
        if (userCancelledTransfer) {
            userCancelledTransfer = false
            autoRearmCount = 0
            disarmSync()
            updateNfcListening()
            setTransferPhase(NfcTransferPhase.FAILED, getString(R.string.nfc_status_cancelled))
            showPostTransferActions()
            return
        }
        if (result.success) {
            lastSuccessfulSyncAtMs = System.currentTimeMillis()
            autoRearmCount = 0
            val wasClearPanel = clearPanelMode
            clearPanelMode = false
            preferRev22Transfer = false
            disarmSync()
            updateNfcListening()
            updateProgressBar(100)
            setTransferPhase(
                NfcTransferPhase.SUCCESS,
                if (wasClearPanel) {
                    getString(R.string.nfc_status_success_clear)
                } else {
                    getString(R.string.nfc_status_success_refresh)
                },
            )
            showPostTransferActions()
            return
        }
        // Transient failure (dead tag handle, module busy): stay armed so the imminent
        // re-discovery (~0.3s while the phone is still held) retries with a fresh handle.
        if (result.retryable && !result.suppressAutoRearm && autoRearmCount < MAX_AUTO_REARMS) {
            autoRearmCount++
            Log.i(TAG, "Auto re-arm $autoRearmCount/$MAX_AUTO_REARMS after retryable failure")
            updateNfcListening()
            setTransferPhase(
                NfcTransferPhase.DISCOVERING,
                getString(R.string.nfc_status_auto_retry),
            )
            return
        }
        autoRearmCount = 0
        disarmSync()
        updateNfcListening()
        setTransferPhase(NfcTransferPhase.FAILED, result.message)
        showPostTransferActions()
    }

    private fun prepareTransferPayload() {
        if (preparedPayloadBitmap != null) return
        val bitmap = mBitmap ?: return
        val sdkType = WavesharePanel.sdkTypeForPreferences(preferences)
        val expected = WavesharePanel.expectedPixels(sdkType)
        preparedPayloadBitmap = WaveshareBitmapPrep.prepareForOfficial(bitmap, expected.first, expected.second)
        Log.i(
            TAG,
            "Prepared ${expected.first}x${expected.second} bitmap for official engine",
        )
    }

    private fun beginTransfer(tag: Tag, waveshareAar: Boolean) {
        val bitmap = mBitmap
        if (bitmap == null) {
            setTransferPhase(NfcTransferPhase.FAILED, "missing image")
            showPostTransferActions()
            return
        }
        if (!transferPending.compareAndSet(false, true)) {
            Log.d(TAG, "Ignoring duplicate tag while transfer is starting")
            return
        }

        val techSummary = EInkDriverRegistry.describeTag(tag)
        Log.i(TAG, "Transfer requested · $techSummary")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val hints = TagHints(
                    waveshareNdef = waveshareAar,
                    suggestedScreenSizeKey = preferences.getScreenSize(),
                    suggestedWaveshareSdkType = WavesharePanel.sdkTypeForPreferences(preferences),
                )

                if (isWaveshareTag(tag, waveshareAar)) {
                    val driver = EInkDriverRegistry.driverById(WaveshareTagProbe.DRIVER_ID)
                    if (driver == null) {
                        setTransferPhase(NfcTransferPhase.TAG_WRONG, techSummary)
                        return@launch
                    }
                    Log.i(TAG, "Waveshare fast-path · $techSummary")
                    flashWithDriver(
                        driver,
                        tag,
                        bitmap,
                        profile = WavesharePanel.transferProfile(preferences),
                    )
                    return@launch
                }

                setTransferPhase(NfcTransferPhase.DISCOVERING, getString(R.string.nfc_status_discovering))
                val uidHex = tagUidHex(tag)
                val cached = preferences.getCachedTagProfile(uidHex)
                val resolved = EInkDriverRegistry.resolveDriver(
                    this@NfcFlasher,
                    tag,
                    hints,
                    cachedProfile = cached,
                    passiveOnly = true,
                )
                val driver = resolved?.first
                if (driver == null) {
                    Log.w(TAG, "No driver for tag: $techSummary")
                    setTransferPhase(
                        NfcTransferPhase.TAG_WRONG,
                        getString(R.string.nfc_status_detail_tech, techSummary),
                    )
                    return@launch
                }

                val profile = resolved.second
                if (profile != null) {
                    preferences.cacheTagProfile(uidHex, profile)
                }

                Log.i(TAG, "Starting transfer via ${driver.name} · $techSummary")
                flashWithDriver(driver, tag, bitmap, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed", e)
                withContext(Dispatchers.Main) {
                    setTransferPhase(NfcTransferPhase.FAILED, e.message ?: "Transfer failed")
                    showPostTransferActions()
                }
            } finally {
                transferPending.set(false)
            }
        }
    }

    private fun armSync(bypassCooldown: Boolean = false) {
        if (mBitmap == null) {
            setTransferPhase(NfcTransferPhase.FAILED, "missing image")
            return
        }
        val cooldownMs = if (bypassCooldown) 0L else moduleCooldownRemainingMs()
        if (cooldownMs > 0) {
            val waitSec = ((cooldownMs + 999) / 1000).toInt()
            Log.i(TAG, "Sync blocked — ${waitSec}s cooldown remaining after last successful sync")
            Toast.makeText(
                this,
                getString(R.string.nfc_sync_cooldown_toast, waitSec),
                Toast.LENGTH_LONG,
            ).show()
            setTransferPhase(
                NfcTransferPhase.LISTENING,
                getString(R.string.nfc_status_cooldown, waitSec),
            )
            return
        }
        syncArmed = true
        autoRearmCount = 0
        postTransferActions?.visibility = View.GONE
        preparedPayloadBitmap = null
        mImgFileUri?.path?.let { path ->
            mBitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options())
        }
        updateSyncArmedUi()
        prepareTransferPayload()
        if (NfcHelper.isEnabled(this)) {
            updateNfcListening()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setTransferPhase(
                NfcTransferPhase.LISTENING,
                getString(R.string.hold_phone_to_flash_text),
            )
            Log.i(TAG, "Sync armed — hold phone on module to transfer")
        } else {
            refreshNfcRadioStatus()
        }
    }

    private fun disarmSync() {
        syncArmed = false
        updateSyncArmedUi()
        if (!mIsFlashing) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateSyncArmedUi() {
        btnStartSync?.visibility = if (!syncArmed && !mIsFlashing) View.VISIBLE else View.GONE
    }

    private fun moduleCooldownRemainingMs(): Long {
        if (lastSuccessfulSyncAtMs <= 0L) return 0L
        val elapsed = System.currentTimeMillis() - lastSuccessfulSyncAtMs
        return (MODULE_COOLDOWN_MS - elapsed).coerceAtLeast(0L)
    }

    private suspend fun flashWithDriver(
        driver: com.joshuatz.nfceinkwriter.nfc.EInkNfcDriver,
        tag: Tag,
        bitmap: Bitmap,
        profile: com.joshuatz.nfceinkwriter.nfc.discovery.EInkTagProfile? = null,
    ) {
        withContext(Dispatchers.Main) {
            mIsFlashing = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setTransferPhase(NfcTransferPhase.TRANSFERRING, driver.name)
        }
        val session = NfcFlashSession(
            context = this@NfcFlasher,
            screenSizeEnum = profile?.waveshareSdkType
                ?: WavesharePanel.sdkTypeForPreferences(preferences),
            colorMode = profile?.colorMode ?: preferences.getColorMode(),
            profile = profile,
            devicePassword = preferences.getDevicePassword(),
        )
        val result = withContext(Dispatchers.IO) {
            // Keep reader mode active for the whole transfer — disabling it invalidates the Tag.
            driver.sendBitmap(tag, bitmap, session) { progress ->
                runOnUiThread { updateProgressBar(progress) }
            }
        }
        withContext(Dispatchers.Main) {
            disableNfcListening()
            mIsFlashing = false
            disarmSync()
            updateNfcListening()
            if (result.success) {
                setTransferPhase(
                    NfcTransferPhase.SUCCESS,
                    getString(R.string.nfc_status_success_refresh),
                )
            } else {
                setTransferPhase(NfcTransferPhase.FAILED, result.message)
            }
            showPostTransferActions()
        }
    }

    private fun refreshNfcRadioStatus() {
        if (mIsFlashing) return
        when (NfcHelper.getRadioState(this)) {
            NfcRadioState.UNAVAILABLE -> setTransferPhase(NfcTransferPhase.NFC_UNAVAILABLE)
            NfcRadioState.DISABLED -> setTransferPhase(NfcTransferPhase.NFC_DISABLED)
            NfcRadioState.ENABLED -> {
                if (currentPhase == NfcTransferPhase.NFC_UNAVAILABLE ||
                    currentPhase == NfcTransferPhase.NFC_DISABLED
                ) {
                    if (syncArmed) {
                        setTransferPhase(
                            NfcTransferPhase.LISTENING,
                            getString(R.string.hold_phone_to_flash_text),
                        )
                    } else if (lastDetectedSummary != null) {
                        setTransferPhase(
                            NfcTransferPhase.TAG_SEEN,
                            getString(R.string.nfc_status_module_waiting, lastDetectedSummary!!),
                        )
                    } else {
                        setTransferPhase(NfcTransferPhase.LISTENING, getString(R.string.nfc_status_ready_detail))
                    }
                }
            }
        }
    }

    private fun setTransferPhase(phase: NfcTransferPhase, detail: String? = null) {
        currentPhase = phase
        runOnUiThread {
            val dotColor = when (phase) {
                NfcTransferPhase.NFC_UNAVAILABLE, NfcTransferPhase.NFC_DISABLED, NfcTransferPhase.FAILED ->
                    R.color.sankara_red
                NfcTransferPhase.TAG_WRONG -> R.color.sankara_gold
                NfcTransferPhase.DISCOVERING, NfcTransferPhase.TRANSFERRING -> R.color.sankara_gold
                NfcTransferPhase.SUCCESS -> R.color.sankara_green
                else -> R.color.sankara_green
            }
            statusDot?.background?.setColorFilter(
                ContextCompat.getColor(this, dotColor),
                PorterDuff.Mode.SRC_IN,
            )

            val titleRes = when (phase) {
                NfcTransferPhase.NFC_UNAVAILABLE -> R.string.nfc_status_unavailable
                NfcTransferPhase.NFC_DISABLED -> R.string.nfc_status_disabled
                NfcTransferPhase.LISTENING -> R.string.nfc_status_listening
                NfcTransferPhase.DISCOVERING -> R.string.nfc_status_discovering
                NfcTransferPhase.TAG_SEEN -> R.string.nfc_status_tag_seen
                NfcTransferPhase.TAG_WRONG -> R.string.nfc_status_tag_wrong
                NfcTransferPhase.TRANSFERRING -> R.string.nfc_status_transferring
                NfcTransferPhase.SUCCESS -> R.string.nfc_status_success
                NfcTransferPhase.FAILED -> R.string.nfc_status_failed
            }

            statusTitle?.text = when {
                phase == NfcTransferPhase.LISTENING && !syncArmed && !mIsFlashing ->
                    getString(R.string.nfc_status_ready)
                phase == NfcTransferPhase.FAILED -> getString(titleRes, detail ?: "unknown")
                else -> getString(titleRes)
            }
            statusDetail?.text = when {
                detail != null -> detail
                phase == NfcTransferPhase.LISTENING && !syncArmed ->
                    getString(R.string.nfc_status_ready_detail)
                phase == NfcTransferPhase.LISTENING && syncArmed ->
                    getString(R.string.hold_phone_to_flash_text)
                phase == NfcTransferPhase.NFC_DISABLED -> getString(R.string.settings_nfc_hint)
                phase == NfcTransferPhase.SUCCESS -> detail ?: ""
                else -> ""
            }
            btnOpenNfcSettings?.visibility = if (
                phase == NfcTransferPhase.NFC_DISABLED || phase == NfcTransferPhase.NFC_UNAVAILABLE
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun showPostTransferActions() {
        runOnUiThread {
            postTransferActions?.visibility = View.VISIBLE
        }
    }

    private fun updateNfcListening() {
        if (!NfcHelper.isEnabled(this)) {
            disableNfcListening()
            return
        }
        if (mIsFlashing || transferPending.get()) return
        disableForegroundDispatch()
        enableReaderMode()
    }

    private fun disableNfcListening() {
        disableForegroundDispatch()
        disableReaderMode()
    }

    private fun enableForegroundDispatch() {
        if (foregroundDispatchActive) return
        val adapter = mNfcAdapter ?: return
        val pending = mPendingIntent ?: return
        val filters = mNfcIntentFilters ?: return
        try {
            adapter.enableForegroundDispatch(this, pending, filters, mNfcTechList)
            foregroundDispatchActive = true
            Log.i(TAG, "Foreground dispatch enabled · armed=$syncArmed")
        } catch (e: Exception) {
            Log.e(TAG, "enableForegroundDispatch failed", e)
        }
    }

    private fun disableForegroundDispatch() {
        if (!foregroundDispatchActive) return
        try {
            mNfcAdapter?.disableForegroundDispatch(this)
            foregroundDispatchActive = false
        } catch (e: Exception) {
            Log.w(TAG, "disableForegroundDispatch failed", e)
            foregroundDispatchActive = false
        }
    }

    private fun enableReaderMode() {
        if (readerModeActive) return
        val adapter = mNfcAdapter ?: return
        try {
            // The module stops answering presence-check APDUs while it physically refreshes the
            // e-ink (the ~99% phase). At the default ~125ms cadence that reads as a false tag-loss
            // and tears down the field mid-refresh. Backing the cadence way off keeps the field
            // steady through the repaint. This is the only field-stability lever Android exposes —
            // raw RF transmit power is fixed in the controller and not adjustable by any app.
            val readerExtras = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, READER_PRESENCE_CHECK_DELAY_MS)
            }
            adapter.enableReaderMode(
                this,
                { tag -> onReaderTagDiscovered(tag) },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                readerExtras,
            )
            readerModeActive = true
            disableForegroundDispatch()
            Log.i(TAG, "Reader mode enabled · armed=$syncArmed")
        } catch (e: Exception) {
            Log.e(TAG, "enableReaderMode failed — falling back to foreground dispatch", e)
            enableForegroundDispatch()
        }
    }

    private fun disableReaderMode() {
        if (!readerModeActive) return
        try {
            mNfcAdapter?.disableReaderMode(this)
            Log.d(TAG, "Reader mode disabled")
        } catch (e: Exception) {
            Log.w(TAG, "disableReaderMode failed", e)
        }
        readerModeActive = false
    }

    private fun startNfcCheckLoop() {
        if (mNfcCheckHandler == null) {
            mNfcCheckHandler = Handler(Looper.getMainLooper())
            mNfcCheckHandler?.postDelayed(mNfcCheckCallback, mNfcCheckIntervalMs)
        }
    }

    private fun stopNfcCheckLoop() {
        mNfcCheckHandler?.removeCallbacks(mNfcCheckCallback)
        mNfcCheckHandler = null
    }

    private fun checkNfcAndAttemptRecover() {
        if (mIsFlashing || transferPending.get()) return
        refreshNfcRadioStatus()
        if (!NfcHelper.isEnabled(this) ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return
        }
        updateNfcListening()
    }

    private fun updateProgressBar(updated: Int) {
        mProgressBar?.setProgress(updated, true)
        progressPercentView?.text = "$updated%"
    }

    companion object {
        private const val TAG = "NfcFlasher"
        private const val READER_HINT_DEBOUNCE_MS = 800L
        /** Min gap after success before another sync — module often still refreshing. */
        private const val MODULE_COOLDOWN_MS = 90_000L
        private const val KEY_LAST_SUCCESS_MS = "last_successful_sync_at_ms"
        private const val KEY_PREFER_REV22 = "prefer_rev22_transfer"
        /** Presence-check cadence while connected; high so the e-ink refresh isn't interrupted. */
        private const val READER_PRESENCE_CHECK_DELAY_MS = 5_000
        /** Max automatic fresh-handle retries before asking the user to lift and wait. */
        private const val MAX_AUTO_REARMS = 2
    }
}
