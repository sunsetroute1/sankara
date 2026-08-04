package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card Studio — build QR, Wi-Fi, contact, and link cards
 * with a live e-ink preview, then adjust in the image editor.
 */
class CardStudioActivity : ThemedActivity() {

    private enum class CardType { QR, WIFI, CONTACT, LINKS }

    private lateinit var prefs: Preferences
    private lateinit var draft: CardStudioDraft
    private lateinit var history: CardStudioHistory
    private lateinit var previewView: ImageView
    private lateinit var recentScroll: HorizontalScrollView
    private lateinit var recentContainer: LinearLayout
    private lateinit var recentEmpty: View
    private lateinit var recentSubtitle: View
    private lateinit var chipClearForm: Chip
    private var previewBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var debounceJob: Job? = null
    private var previewGeneration = 0
    private var cardType = CardType.QR
    private var suppressTypeChange = false
    private var exitConfirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_studio)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.cardStudioAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.cardStudioScroll))

        prefs = Preferences(this)
        draft = CardStudioDraft(this)
        history = CardStudioHistory(this)
        previewView = findViewById(R.id.cardPreviewImage)
        recentScroll = findViewById(R.id.recentCardsScroll)
        recentContainer = findViewById(R.id.recentCardsContainer)
        recentEmpty = findViewById(R.id.recentCardsEmpty)
        recentSubtitle = findViewById(R.id.recentCardsSubtitle)
        chipClearForm = findViewById(R.id.chipClearForm)

        findViewById<MaterialToolbar>(R.id.card_studio_toolbar).setNavigationOnClickListener {
            requestExit()
        }

        findViewById<ChipGroup>(R.id.cardTypeChips).setOnCheckedStateChangeListener { group, checkedIds ->
            if (suppressTypeChange) return@setOnCheckedStateChangeListener
            val newType = when (checkedIds.firstOrNull()) {
                R.id.chipTypeWifi -> CardType.WIFI
                R.id.chipTypeContact -> CardType.CONTACT
                R.id.chipTypeLinks -> CardType.LINKS
                else -> CardType.QR
            }
            if (newType != cardType && hasContent()) {
                val revertId = chipIdForType(cardType)
                MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))
                    .setTitle(R.string.card_studio_switch_type_title)
                    .setMessage(R.string.card_studio_switch_type_message)
                    .setPositiveButton(R.string.card_studio_switch_continue) { _, _ ->
                        applyCardType(newType)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        suppressTypeChange = true
                        group.check(revertId)
                        suppressTypeChange = false
                    }
                    .show()
                return@setOnCheckedStateChangeListener
            }
            applyCardType(newType)
        }

        findViewById<ChipGroup>(R.id.wifiSecurityChips).setOnCheckedStateChangeListener { _, _ ->
            schedulePreview(immediate = true)
        }

        val watchedFields = allFieldIds()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateClearFormVisibility()
                schedulePreview(immediate = false)
            }
        }
        for (id in watchedFields) {
            findViewById<EditText>(id).addTextChangedListener(watcher)
        }

        findViewById<MaterialButton>(R.id.btnCardSync).setOnClickListener { openInImageEditor() }
        findViewById<MaterialButton>(R.id.btnCardCancel).setOnClickListener { requestExit() }
        chipClearForm.setOnClickListener { clearForm(showToast = true) }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    requestExit()
                }
            },
        )

        restoreDraftIfPresent()
        updateFieldVisibility()
        refreshRecentCards()
        updateClearFormVisibility()
        schedulePreview(immediate = true)
    }

    override fun onResume() {
        super.onResume()
        refreshRecentCards()
    }

    override fun onPause() {
        super.onPause()
        if (!exitConfirmed && hasContent()) {
            draft.save(captureSnapshot())
        }
    }

    private fun applyCardType(newType: CardType) {
        cardType = newType
        updateFieldVisibility()
        schedulePreview(immediate = true)
    }

    private fun chipIdForType(type: CardType): Int = when (type) {
        CardType.QR -> R.id.chipTypeQr
        CardType.WIFI -> R.id.chipTypeWifi
        CardType.CONTACT -> R.id.chipTypeContact
        CardType.LINKS -> R.id.chipTypeLinks
    }

    private fun allFieldIds(): List<Int> = listOf(
        R.id.fieldQrContent, R.id.fieldQrLabel,
        R.id.fieldWifiSsid, R.id.fieldWifiPassword,
        R.id.fieldContactName, R.id.fieldContactTitle, R.id.fieldContactPhone,
        R.id.fieldContactEmail, R.id.fieldContactWebsite,
        R.id.fieldLinksHandle, R.id.fieldLink1, R.id.fieldLink2, R.id.fieldLink3, R.id.fieldLink4,
    )

    private fun updateFieldVisibility() {
        findViewById<View>(R.id.groupQr).visibility = if (cardType == CardType.QR) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupWifi).visibility = if (cardType == CardType.WIFI) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupContact).visibility = if (cardType == CardType.CONTACT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupLinks).visibility = if (cardType == CardType.LINKS) View.VISIBLE else View.GONE
    }

    private fun fieldText(id: Int): String = findViewById<EditText>(id).text?.toString()?.trim() ?: ""

    private fun setFieldText(id: Int, value: String) {
        findViewById<EditText>(id).setText(value)
    }

    private fun hasContent(): Boolean = captureSnapshot().hasContent()

    private fun captureSnapshot(): CardStudioSnapshot {
        val fields = linkedMapOf(
            CardStudioSnapshot.KEY_QR_CONTENT to fieldText(R.id.fieldQrContent),
            CardStudioSnapshot.KEY_QR_LABEL to fieldText(R.id.fieldQrLabel),
            CardStudioSnapshot.KEY_WIFI_SSID to fieldText(R.id.fieldWifiSsid),
            CardStudioSnapshot.KEY_WIFI_PASSWORD to fieldText(R.id.fieldWifiPassword),
            CardStudioSnapshot.KEY_CONTACT_NAME to fieldText(R.id.fieldContactName),
            CardStudioSnapshot.KEY_CONTACT_TITLE to fieldText(R.id.fieldContactTitle),
            CardStudioSnapshot.KEY_CONTACT_PHONE to fieldText(R.id.fieldContactPhone),
            CardStudioSnapshot.KEY_CONTACT_EMAIL to fieldText(R.id.fieldContactEmail),
            CardStudioSnapshot.KEY_CONTACT_WEBSITE to fieldText(R.id.fieldContactWebsite),
            CardStudioSnapshot.KEY_LINKS_HANDLE to fieldText(R.id.fieldLinksHandle),
            CardStudioSnapshot.KEY_LINK_1 to fieldText(R.id.fieldLink1),
            CardStudioSnapshot.KEY_LINK_2 to fieldText(R.id.fieldLink2),
            CardStudioSnapshot.KEY_LINK_3 to fieldText(R.id.fieldLink3),
            CardStudioSnapshot.KEY_LINK_4 to fieldText(R.id.fieldLink4),
        )
        return CardStudioSnapshot(
            cardType = when (cardType) {
                CardType.QR -> CardStudioSnapshot.TYPE_QR
                CardType.WIFI -> CardStudioSnapshot.TYPE_WIFI
                CardType.CONTACT -> CardStudioSnapshot.TYPE_CONTACT
                CardType.LINKS -> CardStudioSnapshot.TYPE_LINKS
            },
            wifiSecurity = wifiSecurity(),
            fields = fields,
        )
    }

    private fun applySnapshot(snapshot: CardStudioSnapshot) {
        cardType = when (snapshot.cardType) {
            CardStudioSnapshot.TYPE_WIFI -> CardType.WIFI
            CardStudioSnapshot.TYPE_CONTACT -> CardType.CONTACT
            CardStudioSnapshot.TYPE_LINKS -> CardType.LINKS
            else -> CardType.QR
        }
        suppressTypeChange = true
        findViewById<ChipGroup>(R.id.cardTypeChips).check(chipIdForType(cardType))
        suppressTypeChange = false

        when (snapshot.wifiSecurity) {
            "WEP" -> findViewById<ChipGroup>(R.id.wifiSecurityChips).check(R.id.chipWifiWep)
            "nopass" -> findViewById<ChipGroup>(R.id.wifiSecurityChips).check(R.id.chipWifiOpen)
            else -> findViewById<ChipGroup>(R.id.wifiSecurityChips).check(R.id.chipWifiWpa)
        }

        setFieldText(R.id.fieldQrContent, snapshot.fields[CardStudioSnapshot.KEY_QR_CONTENT].orEmpty())
        setFieldText(R.id.fieldQrLabel, snapshot.fields[CardStudioSnapshot.KEY_QR_LABEL].orEmpty())
        setFieldText(R.id.fieldWifiSsid, snapshot.fields[CardStudioSnapshot.KEY_WIFI_SSID].orEmpty())
        setFieldText(R.id.fieldWifiPassword, snapshot.fields[CardStudioSnapshot.KEY_WIFI_PASSWORD].orEmpty())
        setFieldText(R.id.fieldContactName, snapshot.fields[CardStudioSnapshot.KEY_CONTACT_NAME].orEmpty())
        setFieldText(R.id.fieldContactTitle, snapshot.fields[CardStudioSnapshot.KEY_CONTACT_TITLE].orEmpty())
        setFieldText(R.id.fieldContactPhone, snapshot.fields[CardStudioSnapshot.KEY_CONTACT_PHONE].orEmpty())
        setFieldText(R.id.fieldContactEmail, snapshot.fields[CardStudioSnapshot.KEY_CONTACT_EMAIL].orEmpty())
        setFieldText(R.id.fieldContactWebsite, snapshot.fields[CardStudioSnapshot.KEY_CONTACT_WEBSITE].orEmpty())
        setFieldText(R.id.fieldLinksHandle, snapshot.fields[CardStudioSnapshot.KEY_LINKS_HANDLE].orEmpty())
        setFieldText(R.id.fieldLink1, snapshot.fields[CardStudioSnapshot.KEY_LINK_1].orEmpty())
        setFieldText(R.id.fieldLink2, snapshot.fields[CardStudioSnapshot.KEY_LINK_2].orEmpty())
        setFieldText(R.id.fieldLink3, snapshot.fields[CardStudioSnapshot.KEY_LINK_3].orEmpty())
        setFieldText(R.id.fieldLink4, snapshot.fields[CardStudioSnapshot.KEY_LINK_4].orEmpty())

        updateFieldVisibility()
        updateClearFormVisibility()
        schedulePreview(immediate = true)
    }

    private fun restoreDraftIfPresent() {
        val saved = draft.load() ?: return
        applySnapshot(saved)
        Toast.makeText(this, R.string.card_studio_draft_restored, Toast.LENGTH_SHORT).show()
    }

    private fun clearForm(showToast: Boolean) {
        allFieldIds().forEach { setFieldText(it, "") }
        draft.clear()
        updateClearFormVisibility()
        schedulePreview(immediate = true)
        if (showToast) {
            Toast.makeText(this, R.string.card_studio_clear_form, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateClearFormVisibility() {
        chipClearForm.visibility = if (hasContent()) View.VISIBLE else View.GONE
    }

    private fun wifiSecurity(): String =
        when (findViewById<ChipGroup>(R.id.wifiSecurityChips).checkedChipId) {
            R.id.chipWifiWep -> "WEP"
            R.id.chipWifiOpen -> "nopass"
            else -> "WPA"
        }

    private fun snapshotInputs(): (width: Int, height: Int) -> Bitmap {
        val snapshot = captureSnapshot()
        return { w, h -> CardStudioRenderer.render(snapshot, w, h) }
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

    private fun refreshRecentCards() {
        val entries = history.listEntries()
        recentContainer.removeAllViews()
        if (entries.isEmpty()) {
            recentScroll.visibility = View.GONE
            recentSubtitle.visibility = View.GONE
            recentEmpty.visibility = View.VISIBLE
            return
        }
        recentEmpty.visibility = View.GONE
        recentSubtitle.visibility = View.VISIBLE
        recentScroll.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        entries.forEach { entry ->
            val item = inflater.inflate(R.layout.item_card_studio_recent, recentContainer, false)
            val thumb = item.findViewById<ImageView>(R.id.recentThumb)
            val thumbFile = history.thumbnailFile(entry.id)
            if (thumbFile.exists()) {
                BitmapFactory.decodeFile(thumbFile.absolutePath)?.let { bmp ->
                    thumb.setImageBitmap(bmp)
                }
            }
            item.setOnClickListener {
                applySnapshot(entry.snapshot)
                Toast.makeText(this, R.string.card_studio_recent_restored, Toast.LENGTH_SHORT).show()
            }
            item.setOnLongClickListener {
                showRecentActions(entry)
                true
            }
            recentContainer.addView(item)
        }
    }

    private fun showRecentActions(entry: CardStudioHistoryEntry) {
        val actions = arrayOf(
            getString(R.string.card_studio_action_open_editor),
            getString(R.string.card_studio_action_sync_now),
            getString(R.string.card_studio_action_duplicate),
            getString(R.string.card_studio_action_delete),
        )
        MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))
            .setTitle(getString(R.string.card_studio_recent_menu_title, entry.displayLabel()))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openSnapshotInEditor(entry.snapshot)
                    1 -> openSnapshotForSync(entry.snapshot)
                    2 -> {
                        applySnapshot(entry.snapshot)
                        Toast.makeText(this, R.string.card_studio_recent_restored, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        history.deleteEntry(entry.id)
                        refreshRecentCards()
                        Toast.makeText(this, R.string.card_studio_recent_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun requestExit() {
        if (!hasContent()) {
            exitConfirmed = true
            draft.clear()
            finish()
            return
        }
        MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))
            .setTitle(R.string.card_studio_discard_title)
            .setMessage(R.string.card_studio_discard_message)
            .setPositiveButton(R.string.card_studio_save_draft) { _, _ ->
                draft.save(captureSnapshot())
                exitConfirmed = true
                finish()
            }
            .setNegativeButton(R.string.card_studio_discard) { _, _ ->
                draft.clear()
                exitConfirmed = true
                finish()
            }
            .setNeutralButton(R.string.card_studio_keep_editing, null)
            .show()
    }

    private fun openInImageEditor() {
        if (!hasContent()) {
            Toast.makeText(this, getString(R.string.card_studio_empty), Toast.LENGTH_SHORT).show()
            return
        }
        openSnapshotInEditor(captureSnapshot(), saveToHistory = true)
    }

    private fun openSnapshotInEditor(snapshot: CardStudioSnapshot, saveToHistory: Boolean = false) {
        val syncButton = findViewById<MaterialButton>(R.id.btnCardSync)
        syncButton.isEnabled = false
        previewJob?.cancel()
        debounceJob?.cancel()

        val (panelW, panelH) = prefs.getScreenSizePixels()

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val card = CardStudioRenderer.render(snapshot, panelW, panelH)
                    openFileOutput(PickedSourceFilename, MODE_PRIVATE).use { out ->
                        card.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (saveToHistory) {
                        val thumb = Bitmap.createScaledBitmap(
                            card,
                            THUMB_SIZE,
                            (THUMB_SIZE * panelH.toFloat() / panelW).toInt().coerceAtLeast(1),
                            true,
                        )
                        history.addEntry(snapshot, thumb)
                        if (thumb !== card) thumb.recycle()
                    }
                    card.recycle()
                }
                exitConfirmed = true
                draft.clear()
                startActivity(Intent(this@CardStudioActivity, ImageEditActivity::class.java))
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

    private fun openSnapshotForSync(snapshot: CardStudioSnapshot) {
        lifecycleScope.launch {
            try {
                val (panelW, panelH) = prefs.getScreenSizePixels()
                val colorMode = prefs.getColorMode()
                withContext(Dispatchers.Default) {
                    val card = CardStudioRenderer.render(snapshot, panelW, panelH)
                    val eink = EInkImageProcessor.toEInkBitmap(card, panelW, panelH, colorMode)
                    if (eink !== card) card.recycle()
                    openFileOutput(GeneratedImageFilename, MODE_PRIVATE).use { out ->
                        eink.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    eink.recycle()
                }
                LastGeneratedImage.markSaved(this@CardStudioActivity)
                startActivity(Intent(this@CardStudioActivity, NfcFlasher::class.java))
            } catch (e: Exception) {
                Toast.makeText(
                    this@CardStudioActivity,
                    getString(R.string.crop_failed, e.message ?: "save failed"),
                    Toast.LENGTH_LONG,
                ).show()
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
        private const val THUMB_SIZE = 128
    }
}
