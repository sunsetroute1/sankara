package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.canhub.cropper.CropImageView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app crop screen with a visible Done button. CanHub's [CropImageActivity] uses a toolbar
 * menu action that is easy to miss on dark themes / NoActionBar setups.
 */
class ImageCropActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private var cropping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.cropAppBar))

        val sourceUri = IntentCompat.getParcelableExtra(intent, EXTRA_SOURCE_URI, Uri::class.java)
        if (sourceUri == null) {
            Toast.makeText(this, getString(R.string.crop_failed, "missing image"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val aspectX = intent.getIntExtra(EXTRA_ASPECT_X, 1).coerceAtLeast(1)
        val aspectY = intent.getIntExtra(EXTRA_ASPECT_Y, 1).coerceAtLeast(1)

        cropView = findViewById(R.id.cropImageView)
        cropView.guidelines = CropImageView.Guidelines.ON
        cropView.setAspectRatio(aspectX, aspectY)
        cropView.setFixedAspectRatio(true)
        cropView.setImageUriAsync(sourceUri)

        findViewById<MaterialToolbar>(R.id.crop_toolbar).setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnCropCancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        val doneButton = findViewById<MaterialButton>(R.id.btnCropDone)
        doneButton.setOnClickListener { acceptCrop(doneButton) }
    }

    private fun acceptCrop(doneButton: MaterialButton) {
        if (cropping) return
        cropping = true
        doneButton.isEnabled = false
        findViewById<MaterialButton>(R.id.btnCropCancel).isEnabled = false

        lifecycleScope.launch {
            try {
                val cropped = withContext(Dispatchers.Default) {
                    cropView.getCroppedImage(0, 0, CropImageView.RequestSizeOptions.NONE)
                }
                if (cropped == null) {
                    Toast.makeText(
                        this@ImageCropActivity,
                        getString(R.string.crop_failed, "empty crop"),
                        Toast.LENGTH_LONG,
                    ).show()
                    resetCropButtons(doneButton)
                    return@launch
                }

                val outFile = File(cacheDir, CROP_OUTPUT_FILENAME)
                withContext(Dispatchers.IO) {
                    outFile.outputStream().use { out ->
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                cropped.recycle()

                val resultUri = FileProvider.getUriForFile(
                    this@ImageCropActivity,
                    "${packageName}.fileprovider",
                    outFile,
                )
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_RESULT_URI, resultUri),
                )
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ImageCropActivity,
                    getString(R.string.crop_failed, e.message ?: "crop failed"),
                    Toast.LENGTH_LONG,
                ).show()
                resetCropButtons(doneButton)
            }
        }
    }

    private fun resetCropButtons(doneButton: MaterialButton) {
        cropping = false
        doneButton.isEnabled = true
        findViewById<MaterialButton>(R.id.btnCropCancel).isEnabled = true
    }

    companion object {
        const val EXTRA_SOURCE_URI = "$PackageName.cropSourceUri"
        const val EXTRA_RESULT_URI = "$PackageName.cropResultUri"
        const val EXTRA_ASPECT_X = "$PackageName.cropAspectX"
        const val EXTRA_ASPECT_Y = "$PackageName.cropAspectY"
        private const val CROP_OUTPUT_FILENAME = "crop_result.png"
    }
}
