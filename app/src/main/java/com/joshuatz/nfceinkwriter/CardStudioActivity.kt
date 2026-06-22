package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card Studio — build QR, Wi-Fi, contact, and link cards
 * with a live e-ink preview, then sync straight to the case.
 */
class CardStudioActivity : AppCompatActivity() {

    private enum class CardType { QR, WIFI, CONTACT, LINKS }

    private lateinit var prefs: Preferences
    private lateinit var previewView: ImageView
    private var previewBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var debounceJob: Job? = null
    private var previewGeneration = 0
    private var cardType = CardType.QR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_studio)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.cardStudioAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.cardStudioScroll))

        prefs = Preferences(this)
        previewView = findViewById(R.id.cardPreviewImage)

        findViewById<MaterialToolbar>(R.id.card_studio_toolbar).setNavigationOnClickListener { finish() }

        findViewById<ChipGroup>(R.id.cardTypeChips).setOnCheckedStateChangeListener { _, checkedIds ->
            cardType = when (checkedIds.firstOrNull()) {
                R.id.chipTypeWifi -> CardType.WIFI
                R.id.chipTypeContact -> CardType.CONTACT
                R.id.chipTypeLinks -> CardType.LINKS
                else -> CardType.QR
            }
            updateFieldVisibility()
            schedulePreview(immediate = true)
        }

        findViewById<ChipGroup>(R.id.wifiSecurityChips).setOnCheckedStateChangeListener { _, _ ->
            schedulePreview(immediate = true)
        }

        val watchedFields = listOf(
            R.id.fieldQrContent, R.id.fieldQrLabel,
            R.id.fieldWifiSsid, R.id.fieldWifiPassword,
            R.id.fieldContactName, R.id.fieldContactTitle, R.id.fieldContactPhone,
            R.id.fieldContactEmail, R.id.fieldContactWebsite,
            R.id.fieldLinksHandle, R.id.fieldLink1, R.id.fieldLink2, R.id.fieldLink3, R.id.fieldLink4,
        )
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                schedulePreview(immediate = false)
            }
        }
        for (id in watchedFields) {
            findViewById<EditText>(id).addTextChangedListener(watcher)
        }

        findViewById<MaterialButton>(R.id.btnCardSync).setOnClickListener { syncToCase() }
        findViewById<MaterialButton>(R.id.btnCardCancel).setOnClickListener { finish() }

        updateFieldVisibility()
        schedulePreview(immediate = true)
    }

    private fun updateFieldVisibility() {
        findViewById<View>(R.id.groupQr).visibility = if (cardType == CardType.QR) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupWifi).visibility = if (cardType == CardType.WIFI) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupContact).visibility = if (cardType == CardType.CONTACT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupLinks).visibility = if (cardType == CardType.LINKS) View.VISIBLE else View.GONE
    }

    private fun fieldText(id: Int): String = findViewById<EditText>(id).text?.toString()?.trim() ?: ""

    private fun hasContent(): Boolean = when (cardType) {
        CardType.QR -> fieldText(R.id.fieldQrContent).isNotBlank()
        CardType.WIFI -> fieldText(R.id.fieldWifiSsid).isNotBlank()
        CardType.CONTACT -> fieldText(R.id.fieldContactName).isNotBlank()
        CardType.LINKS -> fieldText(R.id.fieldLink1).isNotBlank() ||
            fieldText(R.id.fieldLink2).isNotBlank() ||
            fieldText(R.id.fieldLink3).isNotBlank() ||
            fieldText(R.id.fieldLink4).isNotBlank()
    }

    private fun wifiSecurity(): String =
        when (findViewById<ChipGroup>(R.id.wifiSecurityChips).checkedChipId) {
            R.id.chipWifiWep -> "WEP"
            R.id.chipWifiOpen -> "nopass"
            else -> "WPA"
        }

    /** Renders the card at panel size from current field values. Must be called on the main thread to read fields. */
    private fun snapshotInputs(): (width: Int, height: Int) -> Bitmap {
        return when (cardType) {
            CardType.QR -> {
                val content = QrCodeGenerator.normalizeUrl(fieldText(R.id.fieldQrContent))
                val label = fieldText(R.id.fieldQrLabel)
                ({ w, h -> CardRenderer.renderQrCard(w, h, content, label) })
            }
            CardType.WIFI -> {
                val ssid = fieldText(R.id.fieldWifiSsid)
                val password = fieldText(R.id.fieldWifiPassword)
                val security = wifiSecurity()
                ({ w, h -> CardRenderer.renderWifiCard(w, h, ssid, password, security) })
            }
            CardType.CONTACT -> {
                val name = fieldText(R.id.fieldContactName)
                val title = fieldText(R.id.fieldContactTitle)
                val phone = fieldText(R.id.fieldContactPhone)
                val email = fieldText(R.id.fieldContactEmail)
                val website = fieldText(R.id.fieldContactWebsite)
                ({ w, h -> CardRenderer.renderContactCard(w, h, name, title, phone, email, website) })
            }
            CardType.LINKS -> {
                val handle = fieldText(R.id.fieldLinksHandle)
                val links = listOf(
                    fieldText(R.id.fieldLink1),
                    fieldText(R.id.fieldLink2),
                    fieldText(R.id.fieldLink3),
                    fieldText(R.id.fieldLink4),
                )
                ({ w, h -> CardRenderer.renderLinksCard(w, h, handle, links) })
            }
        }
    }

    private fun schedulePreview(immediate: Boolean) {
        previewJob?.cancel()
        debounceJob?.cancel()
        val generation = ++previewGeneration
        val run = {
            previewJob = lifecycleScope.launch { renderPreview(generation) }
        }
        if (immediate) run() else {
            debounceJob = lifecycleScope.launch {
                delay(DEBOUNCE_MS)
                run()
            }
        }
    }

    private suspend fun renderPreview(generation: Int) {
        val render = snapshotInputs()
        val (panelW, panelH) = prefs.getScreenSizePixels()
        val colorMode = prefs.getColorMode()
        val eink = withContext(Dispatchers.Default) {
            val card = render(panelW, panelH)
            val result = EInkImageProcessor.toEInkBitmap(card, panelW, panelH, colorMode)
            if (result !== card) card.recycle()
            result
        }
        if (generation != previewGeneration) {
            eink.recycle()
            return
        }
        previewBitmap?.recycle()
        previewBitmap = eink
        PanelPreview.bind(previewView, eink, panelW, panelH)
    }

    private fun syncToCase() {
        if (!hasContent()) {
            Toast.makeText(this, getString(R.string.card_studio_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val syncButton = findViewById<MaterialButton>(R.id.btnCardSync)
        syncButton.isEnabled = false
        previewJob?.cancel()
        debounceJob?.cancel()

        val render = snapshotInputs()
        val (panelW, panelH) = prefs.getScreenSizePixels()
        val colorMode = prefs.getColorMode()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val card = render(panelW, panelH)
                    val eink = EInkImageProcessor.toEInkBitmap(card, panelW, panelH, colorMode)
                    if (eink !== card) card.recycle()
                    openFileOutput(GeneratedImageFilename, MODE_PRIVATE).use { out ->
                        eink.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    eink.recycle()
                }
                startActivity(Intent(this@CardStudioActivity, NfcFlasher::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@CardStudioActivity,
                    getString(R.string.crop_failed, e.message ?: "save failed"),
                    Toast.LENGTH_LONG,
                ).show()
                syncButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        previewJob?.cancel()
        debounceJob?.cancel()
        previewBitmap?.recycle()
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 250L
    }
}
