package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImageEditActivity : AppCompatActivity() {

    private lateinit var prefs: Preferences
    private var sourceBitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private val editParams = ImageEditParams()
    private var previewJob: Job? = null
    private var sliderDebounceJob: Job? = null
    private var previewGeneration = 0

    private lateinit var previewView: ImageView
    private lateinit var chipFlipH: Chip
    private lateinit var chipFlipV: Chip
    private lateinit var chipInvert: Chip

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.let { data ->
                IntentCompat.getParcelableExtra(data, ImageCropActivity.EXTRA_RESULT_URI, Uri::class.java)
            }
            if (uri != null) {
                loadBitmapFromUri(uri) { bitmap ->
                    replaceSourceBitmap(bitmap)
                    clearTransformParams()
                    schedulePreview(immediate = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_edit)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.editAppBar))

        prefs = Preferences(this)
        val pixels = prefs.getScreenSizePixels()
        findViewById<TextView>(R.id.editPanelInfo).text =
            getString(R.string.image_preview_panel, prefs.getScreenSize(), pixels.first, pixels.second)

        previewView = findViewById(R.id.editPreviewImage)
        chipFlipH = findViewById(R.id.chipFlipH)
        chipFlipV = findViewById(R.id.chipFlipV)
        chipInvert = findViewById(R.id.chipInvert)

        findViewById<MaterialToolbar>(R.id.edit_toolbar).setNavigationOnClickListener { finish() }

        val sourceFile = getFileStreamPath(PickedSourceFilename)
        if (!sourceFile.exists()) {
            Toast.makeText(this, getString(R.string.crop_failed, "missing source"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val loaded = BitmapFactory.decodeFile(sourceFile.absolutePath)
        if (loaded == null) {
            Toast.makeText(this, getString(R.string.crop_failed, "could not load image"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        sourceBitmap = BitmapUtils.toSoftwareBitmap(loaded)
        setupControls()
        schedulePreview(immediate = true)

        if (intent.getBooleanExtra(EXTRA_AUTO_CROP, false)) {
            previewView.post { launchCrop() }
        }
    }

    private fun setupControls() {
        chipFlipH.isCheckable = true
        chipFlipV.isCheckable = true
        chipInvert.isCheckable = true

        findViewById<Chip>(R.id.chipCrop).setOnClickListener { launchCrop() }
        findViewById<Chip>(R.id.chipRotate).setOnClickListener {
            editParams.rotationQuarterTurns = (editParams.rotationQuarterTurns + 1) % 4
            schedulePreview(immediate = true)
        }
        chipFlipH.setOnCheckedChangeListener { _, checked ->
            editParams.flipHorizontal = checked
            schedulePreview(immediate = true)
        }
        chipFlipV.setOnCheckedChangeListener { _, checked ->
            editParams.flipVertical = checked
            schedulePreview(immediate = true)
        }
        chipInvert.setOnCheckedChangeListener { _, checked ->
            editParams.invert = checked
            schedulePreview(immediate = true)
        }
        findViewById<Chip>(R.id.chipReset).setOnClickListener { resetAll() }
        findViewById<Chip>(R.id.chipEinkEnhance).setOnClickListener { applyEinkEnhance() }

        val contrastSlider = findViewById<Slider>(R.id.sliderContrast)
        val brightnessSlider = findViewById<Slider>(R.id.sliderBrightness)
        val thresholdSlider = findViewById<Slider>(R.id.sliderThreshold)
        contrastSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                editParams.contrast = value
                schedulePreview(immediate = false)
            }
        }
        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                editParams.brightness = value.toInt()
                schedulePreview(immediate = false)
            }
        }
        thresholdSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                editParams.threshold = value.toInt()
                schedulePreview(immediate = false)
            }
        }

        findViewById<MaterialButton>(R.id.btnSyncToCase).setOnClickListener { syncToCase() }
        findViewById<MaterialButton>(R.id.btnPickAgain).setOnClickListener {
            setResult(RESULT_PICK_AGAIN)
            finish()
        }
        findViewById<MaterialButton>(R.id.btnCancelEdit).setOnClickListener { finish() }
    }

    private fun launchCrop() {
        val bitmap = sourceBitmap ?: return
        val file = File(cacheDir, CropTempFilename)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val (panelW, panelH) = prefs.getScreenSizePixels()
        cropLauncher.launch(
            Intent(this, ImageCropActivity::class.java).apply {
                putExtra(ImageCropActivity.EXTRA_SOURCE_URI, uri)
                putExtra(ImageCropActivity.EXTRA_ASPECT_X, panelW)
                putExtra(ImageCropActivity.EXTRA_ASPECT_Y, panelH)
            },
        )
    }

    private fun loadBitmapFromUri(uri: Uri, onLoaded: (Bitmap) -> Unit) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
            if (bitmap == null) {
                Toast.makeText(
                    this@ImageEditActivity,
                    getString(R.string.crop_failed, "crop load failed"),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            onLoaded(bitmap)
        }
    }

    private fun replaceSourceBitmap(bitmap: Bitmap) {
        sourceBitmap?.recycle()
        sourceBitmap = BitmapUtils.toSoftwareBitmap(bitmap)
    }

    private fun clearTransformParams() {
        editParams.flipHorizontal = false
        editParams.flipVertical = false
        editParams.invert = false
        editParams.rotationQuarterTurns = 0
        editParams.contrast = 1f
        editParams.brightness = 0
        editParams.threshold = 128
        syncChipState()
        findViewById<Slider>(R.id.sliderContrast).value = 1f
        findViewById<Slider>(R.id.sliderBrightness).value = 0f
        findViewById<Slider>(R.id.sliderThreshold).value = 128f
    }

    private fun applyEinkEnhance() {
        editParams.applyEinkEnhancePreset()
        findViewById<Slider>(R.id.sliderContrast).value = editParams.contrast
        findViewById<Slider>(R.id.sliderBrightness).value = editParams.brightness.toFloat()
        findViewById<Slider>(R.id.sliderThreshold).value = editParams.threshold.toFloat()
        schedulePreview(immediate = true)
    }

    private fun resetAll() {
        val sourceFile = getFileStreamPath(PickedSourceFilename)
        val loaded = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return
        replaceSourceBitmap(loaded)
        clearTransformParams()
        schedulePreview(immediate = true)
    }

    private fun syncChipState() {
        chipFlipH.isChecked = editParams.flipHorizontal
        chipFlipV.isChecked = editParams.flipVertical
        chipInvert.isChecked = editParams.invert
    }

    private fun schedulePreview(immediate: Boolean) {
        previewJob?.cancel()
        sliderDebounceJob?.cancel()
        val generation = ++previewGeneration
        val runPreview = {
            previewJob = lifecycleScope.launch {
                renderPreview(generation)
            }
        }
        if (immediate) {
            runPreview()
        } else {
            sliderDebounceJob = lifecycleScope.launch {
                delay(SLIDER_DEBOUNCE_MS)
                runPreview()
            }
        }
    }

    private fun buildFinalEinkBitmap(): Bitmap? {
        val source = sourceBitmap ?: return null
        val (panelW, panelH) = prefs.getScreenSizePixels()
        val transformed = BitmapEditor.apply(source, editParams)
        return EInkImageProcessor.toEInkBitmap(transformed, panelW, panelH, prefs.getColorMode())
    }

    private suspend fun renderPreview(generation: Int) {
        val (panelW, panelH) = prefs.getScreenSizePixels()
        val eink = withContext(Dispatchers.Default) {
            buildFinalEinkBitmap()
        } ?: return
        if (generation != previewGeneration) {
            eink.recycle()
            return
        }

        previewBitmap?.recycle()
        previewBitmap = eink
        PanelPreview.bind(previewView, eink, panelW, panelH)
    }

    private fun syncToCase() {
        val syncButton = findViewById<MaterialButton>(R.id.btnSyncToCase)
        syncButton.isEnabled = false
        previewJob?.cancel()
        sliderDebounceJob?.cancel()

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    buildFinalEinkBitmap()
                }
                if (bitmap == null) {
                    Toast.makeText(
                        this@ImageEditActivity,
                        getString(R.string.crop_failed, "nothing to sync"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    syncButton.isEnabled = true
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    openFileOutput(GeneratedImageFilename, MODE_PRIVATE).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                if (bitmap !== previewBitmap) {
                    bitmap.recycle()
                }
                startActivity(Intent(this@ImageEditActivity, NfcFlasher::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ImageEditActivity,
                    getString(R.string.crop_failed, e.message ?: "save failed"),
                    Toast.LENGTH_LONG,
                ).show()
                syncButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        previewJob?.cancel()
        sliderDebounceJob?.cancel()
        sourceBitmap?.recycle()
        previewBitmap?.recycle()
        super.onDestroy()
    }

    companion object {
        const val RESULT_PICK_AGAIN = 2
        const val EXTRA_AUTO_CROP = "com.sankara.app.extra.AUTO_CROP"
        private const val CropTempFilename = "edit_crop_source.png"
        private const val SLIDER_DEBOUNCE_MS = 120L
    }
}
