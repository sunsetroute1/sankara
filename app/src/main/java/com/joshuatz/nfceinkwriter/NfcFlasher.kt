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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joshuatz.nfceinkwriter.nfc.EInkDriverRegistry
import com.joshuatz.nfceinkwriter.nfc.NfcFlashSession
import com.joshuatz.nfceinkwriter.nfc.discovery.TagHints
import com.joshuatz.nfceinkwriter.nfc.discovery.probes.WaveshareTagProbe
import com.joshuatz.nfceinkwriter.nfc.isWaveshareTag
import com.joshuatz.nfceinkwriter.nfc.tagHasNfcA
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
            whileFlashingArea?.visibility =
                if (isFlashing && !clearPanelFlowActive) View.VISIBLE else View.GONE
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
    private var panelSettingsLink: TextView? = null
    private var syncMainContent: View? = null
    private var clearPanelCard: View? = null
    private var clearPanelTitle: TextView? = null
    private var clearPanelDetail: TextView? = null
    private var clearPanelProgress: ProgressBar? = null
    private var clearPanelPercent: TextView? = null
    private var btnCancelClearPanel: MaterialButton? = null
    private var btnClearPanelDone: MaterialButton? = null
    private var btnClearPanelRetry: MaterialButton? = null
    private var nfcToolbar: MaterialToolbar? = null
    /** Dedicated UI while resetting the e-ink panel to white. */
    private var clearPanelFlowActive = false
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
     * Persisted prefs for transfer engine flags across activity recreation.
     */
    private val syncStatePrefs by lazy { getSharedPreferences("nfc_sync_state", MODE_PRIVATE) }
    private var lastReportedProgress = 0
    /** Automatic fresh-handle retries after transient failures (reset on success/manual arm). */
    private var autoRearmCount = 0
    /** Ignore tag discoveries briefly after arming so stale handles expire (lift-and-hold). */
    private var tagDiscoveryBlockedUntilMs = 0L
    private var tagIgnoredDuringCooldown = false
    private var rearmCooldownEndRunnable: Runnable? = null
    /** When set, the next sync sends a solid/test frame instead of user artwork. */
    private var recoveryPattern: PanelTestPattern? = null
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
            if (clearPanelFlowActive) {
                updateClearPanelProgress(lastReportedProgress, elapsedMs)
            } else {
                val detail = transferProgressDetail(lastReportedProgress, elapsedMs)
                setTransferPhase(NfcTransferPhase.TRANSFERRING, detail)
            }
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
        outState.putBoolean("clearPanelFlowActive", clearPanelFlowActive)
        recoveryPattern?.let { outState.putString("recoveryPattern", it.storageKey) }
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
        clearPanelFlowActive = savedInstanceState?.getBoolean("clearPanelFlowActive") ?: false
        recoveryPattern = PanelTestPattern.fromStorageKey(savedInstanceState?.getString("recoveryPattern"))
        if (preferRev22Transfer) {
            Log.i(TAG, "Clearing prefer_rev22 — using official engine only")
            preferRev22Transfer = false
        }

        bindStatusViews()
        bindClearPanelViews()
        panelSettingsLink = findViewById(R.id.nfcPanelSettingsLink)
        syncMainContent = findViewById(R.id.syncMainContent)
        nfcToolbar = findViewById(R.id.nfc_toolbar)
        updatePanelSettingsLink()
        panelSettingsLink?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialToolbar>(R.id.nfc_toolbar).setNavigationOnClickListener {
            requestExit(force = false)
        }
        btnOpenNfcSettings?.setOnClickListener { NfcHelper.openSettings(this) }
        findViewById<MaterialButton>(R.id.btnRetrySync).setOnClickListener {
            postTransferActions?.visibility = View.GONE
            recoveryPattern = null
            armSync()
        }
        findViewById<MaterialButton>(R.id.btnClearPanel).setOnClickListener { startClearPanelFlow() }
        btnStartSync = findViewById(R.id.btnStartSync)
        btnStartSync?.setOnClickListener {
            Log.i(TAG, "Start sync tapped")
            recoveryPattern = null
            armSync()
        }
        findViewById<MaterialButton>(R.id.btnDoneSync).setOnClickListener {
            requestExit(force = true)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    requestExit(force = false)
                }
            },
        )

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
            mBitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options())?.let {
                BitmapUtils.toSoftwareBitmap(it)
            }
        }

        findViewById<ImageView>(R.id.previewImageView).let { preview ->
            bindSyncPreview(preview)
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
        if (clearPanelFlowActive) {
            if (mIsFlashing) {
                showClearPanelUi(ClearPanelUi.TRANSFERRING)
            } else {
                showClearPanelUi(ClearPanelUi.WAITING)
            }
        } else if (savedInstanceState == null) {
            when {
                intent.getBooleanExtra(IntentKeys.StartPanelRecovery, false) -> {
                    val pattern = PanelTestPattern.fromStorageKey(
                        intent.getStringExtra(IntentKeys.PanelRecoveryPattern),
                    ) ?: PanelTestPattern.WHITE
                    findViewById<View>(android.R.id.content).post { startRecoveryFlow(pattern) }
                }
                intent.getBooleanExtra(IntentKeys.StartClearPanel, false) -> {
                    findViewById<View>(android.R.id.content).post {
                        startRecoveryFlow(PanelTestPattern.WHITE)
                    }
                }
            }
        }
        handleIncomingSyncIntent(intent)
    }

    /** Reload generated.png and optionally arm — used when Card Studio / TrailTag push a fresh image. */
    private fun handleIncomingSyncIntent(intent: Intent?) {
        if (intent == null || isNfcTagIntent(intent)) return
        val shouldArm = intent.getBooleanExtra(IntentKeys.ArmSync, false)
        val reloaded = reloadGeneratedImageFromDisk()
        if (reloaded) {
            bindSyncPreview()
            Log.i(TAG, "Reloaded generated image (${mBitmap?.width}x${mBitmap?.height}) arm=$shouldArm")
        } else if (shouldArm) {
            Log.w(TAG, "ArmSync requested but generated.png missing or unreadable")
        }
        if (shouldArm && reloaded && !mIsFlashing && !transferPending.get()) {
            findViewById<View>(android.R.id.content).post {
                if (!mIsFlashing && !transferPending.get()) {
                    armSync()
                }
            }
        }
    }

    /** Fresh read from generated.png — clears cached NFC payload so the next transfer matches disk. */
    private fun reloadGeneratedImageFromDisk(): Boolean {
        preparedPayloadBitmap = null
        mImgFileUri = Uri.fromFile(getFileStreamPath(GeneratedImageFilename))
        val path = mImgFileUri?.path ?: return false
        val file = java.io.File(path)
        if (!file.exists() || file.length() <= 0L) {
            mBitmap = null
            return false
        }
        mBitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options())?.let {
            BitmapUtils.toSoftwareBitmap(it)
        }
        return mBitmap != null
    }

    private enum class ClearPanelUi {
        WAITING, TRANSFERRING, SUCCESS, FAILED,
    }

    private fun bindClearPanelViews() {
        clearPanelCard = findViewById(R.id.clearPanelCard)
        clearPanelTitle = findViewById(R.id.clearPanelTitle)
        clearPanelDetail = findViewById(R.id.clearPanelDetail)
        clearPanelProgress = findViewById(R.id.clearPanelProgress)
        clearPanelPercent = findViewById(R.id.clearPanelPercent)
        btnCancelClearPanel = findViewById(R.id.btnCancelClearPanel)
        btnClearPanelDone = findViewById(R.id.btnClearPanelDone)
        btnClearPanelRetry = findViewById(R.id.btnClearPanelRetry)
        btnCancelClearPanel?.setOnClickListener { cancelClearPanelFlow() }
        btnClearPanelDone?.setOnClickListener { finishClearPanelFlow() }
        btnClearPanelRetry?.setOnClickListener {
            recoveryPattern?.let { startRecoveryFlow(it) } ?: startRecoveryFlow(PanelTestPattern.WHITE)
        }
    }

    private fun startClearPanelFlow() = startRecoveryFlow(PanelTestPattern.WHITE)

    private fun startRecoveryFlow(pattern: PanelTestPattern) {
        postTransferActions?.visibility = View.GONE
        recoveryPattern = pattern
        clearPanelFlowActive = true
        Log.i(TAG, "Panel recovery flow started pattern=${pattern.storageKey}")
        showClearPanelUi(ClearPanelUi.WAITING)
        armSync()
    }

    private fun cancelClearPanelFlow() {
        if (mIsFlashing || transferPending.get()) {
            userCancelledTransfer = true
            OfficialWaveshareDriver.abortActiveTransfer()
        }
        recoveryPattern = null
        disarmSync()
        endClearPanelFlow(showPostActions = true)
    }

    private fun finishClearPanelFlow() {
        recoveryPattern = null
        endClearPanelFlow(showPostActions = true)
    }

    private fun endClearPanelFlow(showPostActions: Boolean) {
        clearPanelFlowActive = false
        nfcToolbar?.setTitle(R.string.nfc_sync_title)
        clearPanelCard?.visibility = View.GONE
        syncMainContent?.visibility = View.VISIBLE
        clearPanelProgress?.visibility = View.GONE
        clearPanelPercent?.visibility = View.GONE
        btnCancelClearPanel?.visibility = View.VISIBLE
        btnClearPanelDone?.visibility = View.GONE
        btnClearPanelRetry?.visibility = View.GONE
        updateNfcListening()
        refreshNfcRadioStatus()
        if (showPostActions) {
            showPostTransferActions()
        }
    }

    private fun showClearPanelUi(state: ClearPanelUi, failureDetail: String? = null) {
        runOnUiThread {
            clearPanelFlowActive = true
            clearPanelCard?.visibility = View.VISIBLE
            syncMainContent?.visibility = View.GONE
            postTransferActions?.visibility = View.GONE
            whileFlashingArea?.visibility = View.GONE
            nfcToolbar?.setTitle(
                if (recoveryPattern == PanelTestPattern.WHITE) {
                    R.string.nfc_clear_panel_title
                } else {
                    R.string.recovery_panel_title
                },
            )
            when (state) {
                ClearPanelUi.WAITING -> {
                    clearPanelTitle?.text = getString(
                        if (recoveryPattern == PanelTestPattern.WHITE) {
                            R.string.nfc_clear_panel_title
                        } else {
                            R.string.recovery_panel_title
                        },
                    )
                    clearPanelDetail?.text = getString(recoveryWaitingMessageRes())
                    clearPanelProgress?.visibility = View.GONE
                    clearPanelPercent?.visibility = View.GONE
                    btnCancelClearPanel?.visibility = View.VISIBLE
                    btnClearPanelDone?.visibility = View.GONE
                    btnClearPanelRetry?.visibility = View.GONE
                }
                ClearPanelUi.TRANSFERRING -> {
                    clearPanelTitle?.text = getString(
                        if (recoveryPattern == PanelTestPattern.WHITE) {
                            R.string.nfc_clear_panel_title
                        } else {
                            R.string.recovery_panel_title
                        },
                    )
                    clearPanelDetail?.text = getString(R.string.recovery_panel_transferring)
                    clearPanelProgress?.visibility = View.VISIBLE
                    clearPanelPercent?.visibility = View.VISIBLE
                    btnCancelClearPanel?.visibility = View.GONE
                    btnClearPanelDone?.visibility = View.GONE
                    btnClearPanelRetry?.visibility = View.GONE
                }
                ClearPanelUi.SUCCESS -> {
                    clearPanelTitle?.text = getString(R.string.nfc_btn_done)
                    clearPanelDetail?.text = getString(R.string.recovery_panel_success)
                    clearPanelProgress?.visibility = View.GONE
                    clearPanelPercent?.visibility = View.GONE
                    btnCancelClearPanel?.visibility = View.GONE
                    btnClearPanelDone?.visibility = View.VISIBLE
                    btnClearPanelRetry?.visibility = View.GONE
                }
                ClearPanelUi.FAILED -> {
                    clearPanelTitle?.setText(R.string.recovery_panel_title)
                    clearPanelDetail?.text = getString(
                        R.string.recovery_panel_failed,
                        failureDetail ?: "unknown",
                    )
                    clearPanelProgress?.visibility = View.GONE
                    clearPanelPercent?.visibility = View.GONE
                    btnCancelClearPanel?.visibility = View.VISIBLE
                    btnClearPanelDone?.visibility = View.GONE
                    btnClearPanelRetry?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun recoveryWaitingMessageRes(): Int = when (recoveryPattern) {
        PanelTestPattern.BLACK -> R.string.recovery_panel_waiting_black
        PanelTestPattern.CHECKERBOARD -> R.string.recovery_panel_waiting_checkerboard
        PanelTestPattern.HORIZONTAL_BARS -> R.string.recovery_panel_waiting_bars
        else -> R.string.recovery_panel_waiting_white
    }

    private fun updateClearPanelProgress(progress: Int, elapsedMs: Long) {
        runOnUiThread {
            if (!clearPanelFlowActive) return@runOnUiThread
            val clamped = progress.coerceIn(0, 100)
            clearPanelProgress?.setProgress(clamped, true)
            clearPanelPercent?.text = "$clamped%"
            clearPanelDetail?.text = when {
                clamped >= 100 && elapsedMs < 15_000L ->
                    getString(R.string.nfc_clear_panel_powering, formatElapsed(elapsedMs))
                clamped >= 100 -> getString(R.string.nfc_clear_panel_success)
                else -> getString(R.string.recovery_panel_transferring)
            }
        }
    }

    private fun bindStatusViews() {
        statusTitle = findViewById(R.id.nfcStatusTitle)
        statusDetail = findViewById(R.id.nfcStatusDetail)
        statusDot = findViewById(R.id.nfcStatusDot)
        btnOpenNfcSettings = findViewById(R.id.btnOpenNfcSettings)
    }

    private fun updatePanelSettingsLink() {
        panelSettingsLink?.text = getString(
            R.string.nfc_panel_settings_link,
            preferences.getScreenSize(),
            preferences.getColorMode().label,
        )
    }

    private fun requestExit(force: Boolean) {
        if (force || (!syncArmed && !mIsFlashing && !transferPending.get())) {
            NfcHelper.promptDisableIfNeeded(this, preferences)
            finish()
            return
        }
        if (mIsFlashing || transferPending.get()) {
            MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))
                .setTitle(
                    if (clearPanelFlowActive) R.string.nfc_exit_clear_title else R.string.nfc_exit_transfer_title,
                )
                .setMessage(
                    if (clearPanelFlowActive) R.string.nfc_exit_clear_message else R.string.nfc_exit_transfer_message,
                )
                .setPositiveButton(R.string.nfc_exit_leave) { _, _ ->
                    userCancelledTransfer = true
                    OfficialWaveshareDriver.abortActiveTransfer()
                    recoveryPattern = null
                    clearPanelFlowActive = false
                    NfcHelper.promptDisableIfNeeded(this, preferences)
                    finish()
                }
                .setNegativeButton(R.string.nfc_exit_stay, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))
            .setTitle(
                if (clearPanelFlowActive) R.string.nfc_exit_clear_title else R.string.nfc_exit_armed_title,
            )
            .setMessage(
                if (clearPanelFlowActive) R.string.nfc_exit_clear_message else R.string.nfc_exit_armed_message,
            )
            .setPositiveButton(R.string.nfc_exit_leave) { _, _ ->
                disarmSync()
                recoveryPattern = null
                clearPanelFlowActive = false
                NfcHelper.promptDisableIfNeeded(this, preferences)
                finish()
            }
            .setNegativeButton(R.string.nfc_exit_stay, null)
            .show()
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
        if (!mIsFlashing && !transferPending.get() && !syncArmed) {
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
            setTransferPhase(NfcTransferPhase.LISTENING, getString(R.string.nfc_status_armed_detail))
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
        } else {
            handleIncomingSyncIntent(intent)
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
            if (clearPanelFlowActive) {
                if (mIsFlashing && clearPanelProgress?.visibility != View.VISIBLE) {
                    showClearPanelUi(ClearPanelUi.TRANSFERRING)
                }
                updateClearPanelProgress(clamped, elapsedMs)
            } else {
                val detail = when {
                    clamped >= 100 && elapsedMs < 15_000L ->
                        getString(R.string.nfc_status_powering_refresh, formatElapsed(elapsedMs)) + "\n" +
                            getString(R.string.nfc_status_hold_still)
                    clamped >= 100 -> getString(R.string.nfc_status_success)
                    else -> transferProgressDetail(clamped, elapsedMs)
                }
                setTransferPhase(NfcTransferPhase.TRANSFERRING, detail)
                updateProgressBar(clamped)
            }
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
        if (syncArmed && System.currentTimeMillis() < tagDiscoveryBlockedUntilMs) {
            tagIgnoredDuringCooldown = true
            Log.d(TAG, "Ignoring tag during re-arm cooldown — will cycle reader when cooldown ends")
            return
        }
        if (!tagHasNfcA(tag)) {
            runOnUiThread { showTagDetected(tag) }
            return
        }
        if (!isWaveshareTag(tag, waveshareAar)) {
            Log.w(TAG, "Unrecognized tag while armed — attempting official transfer anyway")
        }
        if (!transferPending.compareAndSet(false, true)) return
        clearRearmCooldown()

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
        val payload = when (val pattern = recoveryPattern) {
            null -> {
                prepareTransferPayload()
                val source = preparedPayloadBitmap ?: mBitmap
                    ?: return EInkFlashResult(false, "missing image", "Waveshare official")
                preparedPayloadBitmap
                    ?: WaveshareBitmapPrep.prepareForOfficial(source, expected.first, expected.second)
            }
            else -> {
                Log.i(
                    TAG,
                    "Recovery sync — sending ${expected.first}x${expected.second} ${pattern.storageKey} frame",
                )
                WaveshareBitmapPrep.testPattern(expected.first, expected.second, pattern)
            }
        }
        Log.i(
            TAG,
            "Official sync transfer sdkType=$sdkType panel=${expected.first}x${expected.second} " +
                "payload=${payload.width}x${payload.height} recovery=${recoveryPattern?.storageKey} " +
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

        val officialBitmap = if (recoveryPattern == null) {
            WaveshareBitmapPrep.invertForOfficialEngine(payload)
        } else {
            payload
        }

        val official = OfficialWaveshareDriver.transferSync(
            context = this,
            tag = tag,
            bitmap = officialBitmap,
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
        val durationMs = if (transferStartedAtMs > 0L) {
            System.currentTimeMillis() - transferStartedAtMs
        } else {
            0L
        }
        SyncDiagnostics.record(
            context = this,
            success = result.success && !userCancelledTransfer,
            message = result.message,
            driverName = result.driverName,
            maxProgress = lastReportedProgress,
            durationMs = durationMs,
            recoveryPattern = recoveryPattern,
            refreshStalled = result.refreshStalled,
            needsPanelVerify = result.needsPanelVerify,
        )
        if (userCancelledTransfer) {
            userCancelledTransfer = false
            autoRearmCount = 0
            disarmSync()
            updateNfcListening()
            if (clearPanelFlowActive) {
                endClearPanelFlow(showPostActions = true)
            } else {
                setTransferPhase(NfcTransferPhase.FAILED, getString(R.string.nfc_status_cancelled))
                showPostTransferActions()
            }
            return
        }
        if (result.success) {
            autoRearmCount = 0
            LastGeneratedImage.markSaved(this@NfcFlasher)
            val wasRecovery = recoveryPattern != null || clearPanelFlowActive
            recoveryPattern = null
            preferRev22Transfer = false
            disarmSync()
            updateNfcListening()
            if (wasRecovery) {
                mIsFlashing = false
                stopProgressHeartbeat()
                showClearPanelUi(ClearPanelUi.SUCCESS)
                return
            }
            updateProgressBar(100)
            setTransferPhase(
                NfcTransferPhase.SUCCESS,
                when {
                    result.needsPanelVerify -> getString(R.string.nfc_status_success_verify)
                    else -> getString(R.string.nfc_status_success_refresh)
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
            restartNfcListening()
            if (clearPanelFlowActive) {
                showClearPanelUi(ClearPanelUi.WAITING)
            } else {
                setTransferPhase(
                    NfcTransferPhase.DISCOVERING,
                    getString(R.string.nfc_status_auto_retry),
                )
            }
            return
        }
        autoRearmCount = 0
        disarmSync()
        updateNfcListening()
        if (clearPanelFlowActive) {
            showClearPanelUi(ClearPanelUi.FAILED, result.message)
        } else {
            setTransferPhase(NfcTransferPhase.FAILED, result.message)
            showPostTransferActions()
        }
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

    /** Preview matches the exact payload sent over NFC (after WaveshareBitmapPrep). */
    private fun bindSyncPreview(previewView: ImageView = findViewById(R.id.previewImageView)) {
        val bitmap = mBitmap
        if (bitmap == null) {
            previewView.setImageURI(mImgFileUri)
            return
        }
        val (panelW, panelH) = preferences.getScreenSizePixels()
        val payload = preparedPayloadBitmap
            ?: WaveshareBitmapPrep.prepareForOfficial(bitmap, panelW, panelH).also { preparedPayloadBitmap = it }
        // Preview shows expected panel appearance (editor halftone), not the polarity-swapped wire payload.
        PanelPreview.bind(previewView, payload, panelW, panelH)
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

    private fun armSync() {
        if (mBitmap == null && recoveryPattern == null) {
            if (clearPanelFlowActive) {
                showClearPanelUi(ClearPanelUi.FAILED, getString(R.string.crop_failed, "missing image"))
            } else {
                setTransferPhase(NfcTransferPhase.FAILED, "missing image")
            }
            return
        }
        syncArmed = true
        autoRearmCount = 0
        transferPending.set(false)
        lastReaderHintUid = null
        lastReaderHintAtMs = 0L
        beginRearmCooldown()
        postTransferActions?.visibility = View.GONE
        preparedPayloadBitmap = null
        reloadGeneratedImageFromDisk()
        updateSyncArmedUi()
        prepareTransferPayload()
        bindSyncPreview()
        if (NfcHelper.isEnabled(this)) {
            restartNfcListening()
            uiHandler.postDelayed({
                if (syncArmed && !mIsFlashing && !transferPending.get()) {
                    restartNfcListening()
                }
            }, 400L)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!clearPanelFlowActive) {
                setTransferPhase(
                    NfcTransferPhase.LISTENING,
                    getString(R.string.nfc_status_armed_detail),
                )
            }
            Log.i(TAG, "Sync armed — hold phone on module to transfer")
        } else {
            refreshNfcRadioStatus()
        }
    }

    private fun disarmSync() {
        syncArmed = false
        clearRearmCooldown()
        updateSyncArmedUi()
        if (!mIsFlashing) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateSyncArmedUi() {
        btnStartSync?.visibility = if (!syncArmed && !mIsFlashing) View.VISIBLE else View.GONE
    }

    /** Cycle reader mode so a tag already in the field triggers a fresh discovery event. */
    private fun restartNfcListening() {
        disableNfcListening()
        if (!NfcHelper.isEnabled(this)) return
        if (mIsFlashing || transferPending.get()) return
        enableReaderMode()
    }

    /**
     * Brief window after arming where the first tag handle may be stale. When it expires, cycle
     * reader mode so a phone still held on the coil gets a fresh discovery (Android only fires
     * onTagDiscovered once per presence unless reader mode is restarted).
     */
    private fun beginRearmCooldown() {
        clearRearmCooldown()
        tagIgnoredDuringCooldown = false
        tagDiscoveryBlockedUntilMs = System.currentTimeMillis() + REARM_TAG_COOLDOWN_MS
        rearmCooldownEndRunnable = Runnable {
            rearmCooldownEndRunnable = null
            tagDiscoveryBlockedUntilMs = 0L
            if (!syncArmed || mIsFlashing || transferPending.get()) return@Runnable
            Log.i(
                TAG,
                "Re-arm cooldown ended — cycling reader for fresh tag handle" +
                    if (tagIgnoredDuringCooldown) " (phone was on coil during cooldown)" else "",
            )
            tagIgnoredDuringCooldown = false
            restartNfcListening()
        }
        uiHandler.postDelayed(rearmCooldownEndRunnable!!, REARM_TAG_COOLDOWN_MS)
    }

    private fun clearRearmCooldown() {
        rearmCooldownEndRunnable?.let { uiHandler.removeCallbacks(it) }
        rearmCooldownEndRunnable = null
        tagDiscoveryBlockedUntilMs = 0L
        tagIgnoredDuringCooldown = false
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
                    ThemeColors.resolve(this, R.attr.appError)
                NfcTransferPhase.TAG_WRONG -> ThemeColors.resolve(this, R.attr.appWarning)
                NfcTransferPhase.DISCOVERING, NfcTransferPhase.TRANSFERRING -> ThemeColors.resolve(this, R.attr.appWarning)
                NfcTransferPhase.SUCCESS -> ThemeColors.resolve(this, R.attr.appSuccess)
                else -> ThemeColors.resolve(this, R.attr.appSuccess)
            }
            statusDot?.background?.setColorFilter(
                dotColor,
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
        private const val KEY_PREFER_REV22 = "prefer_rev22_transfer"
        /** Presence-check cadence while connected; high so the e-ink refresh isn't interrupted. */
        private const val READER_PRESENCE_CHECK_DELAY_MS = 10_000
        /** After arming, ignore stale tag handles briefly, then cycle reader for phones still on coil. */
        private const val REARM_TAG_COOLDOWN_MS = 1_000L
        /** Max automatic fresh-handle retries before asking the user to lift and wait. */
        private const val MAX_AUTO_REARMS = 3
    }
}
