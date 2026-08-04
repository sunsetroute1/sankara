package com.joshuatz.nfceinkwriter

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class ImagePreviewActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)
        SystemBarUtils.applyStatusBarInset(findViewById(R.id.previewAppBar))

        val prefs = Preferences(this)
        val pixels = prefs.getScreenSizePixels()
        findViewById<TextView>(R.id.previewPanelInfo).text =
            getString(R.string.image_preview_panel, prefs.getScreenSize(), pixels.first, pixels.second)

        val preview = findViewById<ImageView>(R.id.previewImage)
        val imageFile = getFileStreamPath(GeneratedImageFilename)
        if (!imageFile.exists()) {
            Toast.makeText(this, getString(R.string.crop_failed, "missing file"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.crop_failed, "preview load failed"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        PanelPreview.bind(preview, bitmap, pixels.first, pixels.second)

        findViewById<MaterialToolbar>(R.id.preview_toolbar).setNavigationOnClickListener {
            finish()
        }

        findViewById<MaterialButton>(R.id.btnUseImage).setOnClickListener {
            startActivity(Intent(this, NfcFlasher::class.java))
            finish()
        }

        findViewById<MaterialButton>(R.id.btnPickAgain).setOnClickListener {
            setResult(RESULT_PICK_AGAIN)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnCancelPreview).setOnClickListener {
            finish()
        }
    }

    companion object {
        const val RESULT_PICK_AGAIN = 2
    }
}
