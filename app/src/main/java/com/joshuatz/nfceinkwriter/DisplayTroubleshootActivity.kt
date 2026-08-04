package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.joshuatz.nfceinkwriter.DisplayTroubleGuide.Symptom

class DisplayTroubleshootActivity : ThemedActivity() {

    private lateinit var preferences: Preferences
    private var selectedSymptom: Symptom = Symptom.PARTIAL_OVERLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_troubleshoot)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.troubleshootAppBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.troubleshootScroll))
        preferences = Preferences(this)

        findViewById<MaterialToolbar>(R.id.troubleshoot_toolbar).setNavigationOnClickListener {
            finish()
        }

        setupSymptomPicker()
        bindRecoveryButtons()
        refreshLastSyncSummary()
        applyDiagnosis(selectedSymptom)
    }

    override fun onResume() {
        super.onResume()
        refreshLastSyncSummary()
    }

    private fun setupSymptomPicker() {
        val labels = DisplayTroubleGuide.allSymptoms.map { symptomLabel(it) }
        val picker = findViewById<AutoCompleteTextView>(R.id.symptomPicker)
        picker.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels),
        )
        picker.setText(labels.first(), false)
        picker.setOnItemClickListener { _, _, position, _ ->
            selectedSymptom = DisplayTroubleGuide.allSymptoms[position]
            applyDiagnosis(selectedSymptom)
        }
    }

    private fun symptomLabel(symptom: Symptom): String = when (symptom) {
        Symptom.PARTIAL_OVERLAY -> getString(R.string.troubleshoot_symptom_partial_label)
        Symptom.HALF_UPDATED -> getString(R.string.troubleshoot_symptom_half_label)
        Symptom.UNCHANGED -> getString(R.string.troubleshoot_symptom_unchanged_label)
        Symptom.GHOST_IMAGE -> getString(R.string.troubleshoot_symptom_ghost_label)
        Symptom.INVERTED -> getString(R.string.troubleshoot_symptom_inverted_label)
        Symptom.BANDS -> getString(R.string.troubleshoot_symptom_bands_label)
        Symptom.NOISE -> getString(R.string.troubleshoot_symptom_noise_label)
    }

    private fun applyDiagnosis(symptom: Symptom) {
        val diagnosis = DisplayTroubleGuide.diagnosisFor(symptom)
        findViewById<TextView>(R.id.diagnosisTitle).setText(diagnosis.titleRes)
        findViewById<TextView>(R.id.diagnosisCause).setText(diagnosis.causeRes)
        findViewById<TextView>(R.id.diagnosisFix).setText(diagnosis.fixRes)
        findViewById<MaterialButton>(R.id.btnRecommendedRecovery).apply {
            text = getString(
                R.string.troubleshoot_action_recommended_pattern,
                SyncDiagnostics.patternLabel(this@DisplayTroubleshootActivity, diagnosis.recommendedPattern),
            )
            setOnClickListener { launchRecovery(diagnosis.recommendedPattern) }
        }
    }

    private fun bindRecoveryButtons() {
        findViewById<MaterialButton>(R.id.btnRecoveryWhite).setOnClickListener {
            launchRecovery(PanelTestPattern.WHITE)
        }
        findViewById<MaterialButton>(R.id.btnRecoveryBlack).setOnClickListener {
            launchRecovery(PanelTestPattern.BLACK)
        }
        findViewById<MaterialButton>(R.id.btnRecoveryCheckerboard).setOnClickListener {
            launchRecovery(PanelTestPattern.CHECKERBOARD)
        }
        findViewById<MaterialButton>(R.id.btnRecoveryBars).setOnClickListener {
            launchRecovery(PanelTestPattern.HORIZONTAL_BARS)
        }
        findViewById<MaterialButton>(R.id.btnExportReport).setOnClickListener {
            exportReport()
        }
    }

    private fun launchRecovery(pattern: PanelTestPattern) {
        startActivity(
            Intent(this, NfcFlasher::class.java).apply {
                putExtra(IntentKeys.StartPanelRecovery, true)
                putExtra(IntentKeys.PanelRecoveryPattern, pattern.storageKey)
            },
        )
    }

    private fun exportReport() {
        val report = SyncDiagnostics.buildExportReport(this, preferences)
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.troubleshoot_export_subject))
                putExtra(Intent.EXTRA_TEXT, report)
            },
        )
    }

    private fun refreshLastSyncSummary() {
        findViewById<TextView>(R.id.lastSyncSummary).text =
            SyncDiagnostics.formatLatestSummary(this)
    }
}
