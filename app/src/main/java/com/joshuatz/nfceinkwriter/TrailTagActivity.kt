package com.joshuatz.nfceinkwriter



import android.content.Intent

import android.graphics.Bitmap

import android.os.Bundle

import android.view.LayoutInflater

import android.widget.ArrayAdapter

import android.widget.ImageView

import android.widget.Spinner

import android.widget.TextView

import android.widget.TimePicker

import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts

import androidx.lifecycle.lifecycleScope

import com.google.android.material.appbar.MaterialToolbar

import com.google.android.material.button.MaterialButton

import com.google.android.material.chip.ChipGroup

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.google.android.material.textfield.TextInputEditText

import com.joshuatz.nfceinkwriter.trailtag.AdventureStatus

import com.joshuatz.nfceinkwriter.trailtag.SharingMode

import com.joshuatz.nfceinkwriter.trailtag.TrailActivityType

import androidx.core.content.FileProvider

import com.joshuatz.nfceinkwriter.trailtag.TrailTagOfflineQr

import com.joshuatz.nfceinkwriter.trailtag.TrailTagRenderRequest

import com.joshuatz.nfceinkwriter.trailtag.TrailTagRepository

import com.joshuatz.nfceinkwriter.trailtag.TrailTagRenderer

import com.joshuatz.nfceinkwriter.trailtag.TrailTagSession

import com.joshuatz.nfceinkwriter.trailtag.TrailTagStatusResolver

import com.joshuatz.nfceinkwriter.trailtag.TrailTagTemplate

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat

import java.util.Calendar

import java.util.Locale

import java.util.concurrent.TimeUnit



/** TrailTag dashboard — hybrid local-first outdoor safety beacon. */

class TrailTagActivity : ThemedActivity() {



    private lateinit var prefs: Preferences

    private lateinit var repository: TrailTagRepository

    private lateinit var previewView: ImageView

    private var previewBitmap: Bitmap? = null

    private var selectedTemplate = TrailTagTemplate.ACTIVE_ADVENTURE



    private val profileLauncher = registerForActivityResult(

        ActivityResultContracts.StartActivityForResult(),

    ) {

        refreshDashboard()

        renderPreview()

    }



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_trail_tag)

        SystemBarUtils.applyStatusBarInset(findViewById(R.id.trailTagAppBar))

        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.trailTagScroll))



        prefs = Preferences(this)

        repository = TrailTagRepository(this)

        previewView = findViewById(R.id.trailTagPreview)

        selectedTemplate = repository.getSelectedTemplate()



        findViewById<MaterialToolbar>(R.id.trail_tag_toolbar).setNavigationOnClickListener { finish() }



        setupTemplateChips()

        findViewById<MaterialButton>(R.id.btnTrailTagProfile).setOnClickListener {

            profileLauncher.launch(Intent(this, TrailTagProfileActivity::class.java))

        }

        findViewById<MaterialButton>(R.id.btnTrailTagPublish).setOnClickListener { showPublishDialog() }

        findViewById<MaterialButton>(R.id.btnTrailTagStart).setOnClickListener { showStartAdventureDialog() }

        findViewById<MaterialButton>(R.id.btnTrailTagSync).setOnClickListener { syncToEink() }

        findViewById<MaterialButton>(R.id.btnTrailTagEnd).setOnClickListener { endAdventure() }

        findViewById<MaterialButton>(R.id.btnTrailTagPublicProfile).setOnClickListener { openProfileViewer() }

        findViewById<MaterialButton>(R.id.btnTrailTagShareHtml)?.setOnClickListener { shareOfflineHtml() }



        repository.regenerateLocalHtml()

        refreshDashboard()

        renderPreview()

    }



    override fun onResume() {

        super.onResume()

        repository.regenerateLocalHtml()

        refreshDashboard()

        renderPreview()

    }



    private fun setupTemplateChips() {

        val group = findViewById<ChipGroup>(R.id.trailTagTemplateChips)

        val chipId = when (selectedTemplate) {

            TrailTagTemplate.ACTIVE_ADVENTURE -> R.id.chipTemplateActive

            TrailTagTemplate.VEHICLE_TRAILHEAD -> R.id.chipTemplateVehicle

            TrailTagTemplate.EMERGENCY_PROFILE -> R.id.chipTemplateEmergency

        }

        group.check(chipId)

        group.setOnCheckedStateChangeListener { _, checkedIds ->

            selectedTemplate = when (checkedIds.firstOrNull()) {

                R.id.chipTemplateVehicle -> TrailTagTemplate.VEHICLE_TRAILHEAD

                R.id.chipTemplateEmergency -> TrailTagTemplate.EMERGENCY_PROFILE

                else -> TrailTagTemplate.ACTIVE_ADVENTURE

            }

            repository.setSelectedTemplate(selectedTemplate)

            renderPreview()

        }

    }



    private fun refreshDashboard() {

        val profile = repository.getProfile()

        val session = repository.getSession()

        val status = TrailTagStatusResolver.resolve(session)



        findViewById<TextView>(R.id.trailTagProfileName).text = if (profile.hasMinimumContent()) {

            profile.personLabel()

        } else {

            getString(R.string.trail_tag_profile_not_set)

        }

        findViewById<TextView>(R.id.trailTagProfileContacts).text = getString(

            R.string.trail_tag_profile_contacts_count,

            profile.configuredContactCount(),

        )

        findViewById<TextView>(R.id.trailTagProfileTracking).text = getString(

            R.string.trail_tag_profile_tracking_count,

            profile.configuredTrackingCount(),

        )

        findViewById<TextView>(R.id.trailTagSharingMode).text = when {

            profile.isHosted() -> getString(R.string.trail_tag_sharing_hosted, repository.getHostedUrl().orEmpty())

            else -> getString(R.string.trail_tag_sharing_local)

        }



        val publishButton = findViewById<MaterialButton>(R.id.btnTrailTagPublish)

        publishButton.text = getString(

            if (profile.isHosted()) R.string.trail_tag_manage_hosted else R.string.trail_tag_publish_emergency_page,

        )



        val statusLabel = findViewById<TextView>(R.id.trailTagStatusLabel)

        val statusDetail = findViewById<TextView>(R.id.trailTagStatusDetail)

        val statusTimes = findViewById<TextView>(R.id.trailTagStatusTimes)

        val endButton = findViewById<MaterialButton>(R.id.btnTrailTagEnd)

        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())



        when {

            session == null || status == AdventureStatus.NONE -> {

                statusLabel.text = getString(R.string.trail_tag_status_none)

                statusDetail.text = getString(R.string.trail_tag_status_none_detail)

                statusTimes.text = ""

                endButton.isEnabled = false

            }

            status == AdventureStatus.NEEDS_UPDATE -> {

                statusLabel.text = getString(R.string.trail_tag_status_needs_update)

                statusDetail.text = getString(

                    R.string.trail_tag_status_last_activity,

                    session.activityType.label,

                    session.location.ifBlank { "—" },

                )

                statusTimes.text = getString(

                    R.string.trail_tag_status_expected_was,

                    timeFmt.format(session.expectedReturnMs),

                )

                endButton.isEnabled = false

            }

            else -> {

                statusLabel.text = when (status) {

                    AdventureStatus.EMERGENCY -> getString(R.string.trail_tag_status_emergency)

                    AdventureStatus.PAST_RETURN -> getString(R.string.trail_tag_status_past_return)

                    else -> getString(R.string.trail_tag_status_active)

                }

                statusDetail.text = buildString {

                    append(session.activityType.label)

                    if (session.location.isNotBlank()) append(" · ").append(session.location)

                }

                statusTimes.text = getString(

                    R.string.trail_tag_status_times,

                    timeFmt.format(session.startTimeMs),

                    timeFmt.format(session.expectedReturnMs),

                )

                endButton.isEnabled = session.active

            }

        }



        findViewById<MaterialButton>(R.id.btnTrailTagStart).text = getString(

            if (session?.active == true) R.string.trail_tag_update_adventure else R.string.trail_tag_start_adventure,

        )



        val syncMs = LastGeneratedImage.savedAtMs(this)

        findViewById<TextView>(R.id.trailTagDisplaySyncStatus).text = if (syncMs > 0L) {

            getString(R.string.trail_tag_display_synced, formatRelativeTime(syncMs))

        } else {

            getString(R.string.trail_tag_display_not_synced)

        }

    }



    private fun formatRelativeTime(ms: Long): String {

        val delta = System.currentTimeMillis() - ms

        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)

        return when {

            minutes < 1 -> getString(R.string.trail_tag_sync_just_now)

            minutes < 60 -> getString(R.string.trail_tag_sync_minutes_ago, minutes.toInt())

            else -> {

                val hours = TimeUnit.MILLISECONDS.toHours(delta)

                getString(R.string.trail_tag_sync_hours_ago, hours.toInt())

            }

        }

    }



    private fun showPublishDialog() {

        val profile = repository.getProfile()

        if (!profile.hasMinimumContent()) {

            Toast.makeText(this, R.string.trail_tag_profile_required, Toast.LENGTH_LONG).show()

            return

        }



        if (profile.isHosted()) {

            MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))

                .setTitle(R.string.trail_tag_manage_hosted)

                .setMessage(getString(R.string.trail_tag_hosted_url_line, repository.getHostedUrl().orEmpty()))

                .setPositiveButton(R.string.trail_tag_republish) { _, _ -> publishEmergencyPage() }

                .setNeutralButton(R.string.trail_tag_revoke_hosted) { _, _ -> revokeHostedPage() }

                .setNegativeButton(R.string.cancel, null)

                .show()

        } else {

            MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))

                .setTitle(R.string.trail_tag_publish_emergency_page)

                .setMessage(R.string.trail_tag_publish_confirm_message)

                .setPositiveButton(R.string.trail_tag_publish_confirm) { _, _ -> publishEmergencyPage() }

                .setNegativeButton(R.string.cancel, null)

                .show()

        }

    }



    private fun publishEmergencyPage() {

        val result = repository.publishEmergencyPage()

        if (result.success) {

            Toast.makeText(

                this,

                getString(R.string.trail_tag_published, result.url.orEmpty()),

                Toast.LENGTH_LONG,

            ).show()

            refreshDashboard()

            renderPreview()

        } else {

            Toast.makeText(this, result.message ?: getString(R.string.trail_tag_publish_failed), Toast.LENGTH_LONG).show()

        }

    }



    private fun revokeHostedPage() {

        repository.revokeHostedPage()

        Toast.makeText(this, R.string.trail_tag_revoked, Toast.LENGTH_SHORT).show()

        refreshDashboard()

        renderPreview()

    }



    private fun openProfileViewer() {
        val profile = repository.getProfile()
        if (!profile.hasMinimumContent()) {
            Toast.makeText(this, R.string.trail_tag_profile_required, Toast.LENGTH_LONG).show()
            return
        }
        repository.regenerateLocalHtml()
        startActivity(
            Intent(this, TrailTagLocalProfileActivity::class.java).apply {
                putExtra(
                    TrailTagLocalProfileActivity.EXTRA_HTML_PATH,
                    repository.localHtmlIndexFile().absolutePath,
                )
            },
        )
    }

    private fun shareOfflineHtml() {
        val profile = repository.getProfile()
        if (!profile.hasMinimumContent()) {
            Toast.makeText(this, R.string.trail_tag_profile_required, Toast.LENGTH_LONG).show()
            return
        }
        val htmlFile = repository.regenerateLocalHtml()
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", htmlFile)
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.trail_tag_share_subject, profile.personLabel()))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, getString(R.string.trail_tag_share_profile)) },
        )
    }



    private fun showStartAdventureDialog() {

        val profile = repository.getProfile()

        if (!profile.hasMinimumContent()) {

            Toast.makeText(this, R.string.trail_tag_profile_required, Toast.LENGTH_LONG).show()

            return

        }



        val view = LayoutInflater.from(this).inflate(R.layout.dialog_trail_tag_adventure, null)

        val spinner = view.findViewById<Spinner>(R.id.spinnerActivityType)

        spinner.adapter = ArrayAdapter(

            this,

            android.R.layout.simple_spinner_dropdown_item,

            TrailActivityType.entries.map { it.label },

        )



        val existing = repository.getSession()

        view.findViewById<TextInputEditText>(R.id.inputLocation)?.setText(existing?.location.orEmpty())

        view.findViewById<TextInputEditText>(R.id.inputRoute)?.setText(existing?.route.orEmpty())

        view.findViewById<TextInputEditText>(R.id.inputNotes)?.setText(existing?.notes.orEmpty())

        view.findViewById<TextInputEditText>(R.id.inputEmergencyHours)?.setText("2")



        val picker = view.findViewById<TimePicker>(R.id.pickerExpectedReturn)

        val cal = Calendar.getInstance()

        existing?.expectedReturnMs?.let { cal.timeInMillis = it }

        picker.hour = cal.get(Calendar.HOUR_OF_DAY)

        picker.minute = cal.get(Calendar.MINUTE)



        existing?.activityType?.let { type ->

            val idx = TrailActivityType.entries.indexOf(type)

            if (idx >= 0) spinner.setSelection(idx)

        }



        MaterialAlertDialogBuilder(this, AppTheme.applyDialogTheme(this))

            .setTitle(R.string.trail_tag_start_adventure)

            .setView(view)

            .setPositiveButton(R.string.trail_tag_save_adventure) { _, _ ->

                val activityType = TrailActivityType.entries[spinner.selectedItemPosition]

                val location = view.findViewById<TextInputEditText>(R.id.inputLocation)?.text?.toString().orEmpty()

                val route = view.findViewById<TextInputEditText>(R.id.inputRoute)?.text?.toString().orEmpty()

                val notes = view.findViewById<TextInputEditText>(R.id.inputNotes)?.text?.toString().orEmpty()

                val emergencyHours = view.findViewById<TextInputEditText>(R.id.inputEmergencyHours)

                    ?.text?.toString()?.toIntOrNull()?.coerceIn(1, 24) ?: 2



                val returnCal = Calendar.getInstance().apply {

                    set(Calendar.HOUR_OF_DAY, picker.hour)

                    set(Calendar.MINUTE, picker.minute)

                    set(Calendar.SECOND, 0)

                    set(Calendar.MILLISECOND, 0)

                    if (timeInMillis <= System.currentTimeMillis()) {

                        add(Calendar.DAY_OF_YEAR, 1)

                    }

                }

                val expectedReturn = returnCal.timeInMillis

                val emergencyThreshold = expectedReturn + emergencyHours * 3_600_000L



                val session = TrailTagSession(

                    activityType = activityType,

                    location = location.trim(),

                    route = route.trim(),

                    startTimeMs = System.currentTimeMillis(),

                    expectedReturnMs = expectedReturn,

                    emergencyThresholdMs = emergencyThreshold,

                    notes = notes.trim(),

                    active = true,

                )

                repository.saveSession(session)

                refreshDashboard()

                renderPreview()

                Toast.makeText(this, R.string.trail_tag_adventure_saved, Toast.LENGTH_SHORT).show()

            }

            .setNegativeButton(R.string.cancel, null)

            .show()

    }



    private fun endAdventure() {

        val session = repository.getSession() ?: return

        repository.saveSession(session.copy(active = false))

        refreshDashboard()

        renderPreview()

        Toast.makeText(this, R.string.trail_tag_adventure_ended, Toast.LENGTH_SHORT).show()

    }



    private fun buildRenderRequest(): TrailTagRenderRequest {

        val profile = repository.getProfile()

        val session = repository.getSession()

        val status = TrailTagStatusResolver.resolve(session)

        return TrailTagRenderRequest(

            profile = profile,

            session = session,

            template = selectedTemplate,

            status = status,

        )

    }



    private fun renderPreview() {
        val (panelW, panelH) = prefs.getScreenSizePixels()
        val colorMode = prefs.getColorMode()
        lifecycleScope.launch {
            val eink = withContext(Dispatchers.Default) {
                TrailTagRenderer.renderForEink(buildRenderRequest(), panelW, panelH, colorMode)
            }
            previewBitmap?.recycle()
            previewBitmap = eink
            PanelPreview.bind(previewView, eink, panelW, panelH)
        }
    }



    private fun syncToEink() {
        val profile = repository.getProfile()
        if (!profile.hasMinimumContent()) {
            Toast.makeText(this, R.string.trail_tag_profile_required, Toast.LENGTH_LONG).show()
            return
        }

        val syncButton = findViewById<MaterialButton>(R.id.btnTrailTagSync)
        syncButton.isEnabled = false
        val (panelW, panelH) = prefs.getScreenSizePixels()
        val colorMode = prefs.getColorMode()
        val renderRequest = buildRenderRequest()
        if (TrailTagOfflineQr.qrTargetLength(renderRequest.profile, renderRequest.session) >
            TrailTagOfflineQr.MAX_QR_URL_CHARS
        ) {
            Toast.makeText(this, R.string.trail_tag_qr_too_large, Toast.LENGTH_LONG).show()
            syncButton.isEnabled = true
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    repository.regenerateLocalHtml()
                    val eink = TrailTagRenderer.renderForEink(renderRequest, panelW, panelH, colorMode)
                    withContext(Dispatchers.IO) {
                        openFileOutput(GeneratedImageFilename, MODE_PRIVATE).use { out ->
                            eink.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                        }
                    }
                    eink.recycle()
                }
                startActivity(
                    Intent(this@TrailTagActivity, NfcFlasher::class.java).apply {
                        putExtra(IntentKeys.ArmSync, true)
                    },
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@TrailTagActivity,
                    getString(R.string.crop_failed, e.message ?: "sync failed"),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                syncButton.isEnabled = true
            }
        }
    }



    override fun onDestroy() {

        previewBitmap?.recycle()

        previewBitmap = null

        super.onDestroy()

    }

}

