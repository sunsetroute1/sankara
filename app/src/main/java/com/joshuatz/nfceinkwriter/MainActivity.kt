package com.joshuatz.nfceinkwriter



import android.content.ComponentName

import android.content.Context

import android.content.Intent

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.net.Uri

import android.os.Build

import android.os.Bundle

import android.provider.Settings

import android.text.TextUtils

import android.util.Log

import android.widget.ImageView

import android.widget.TextView

import android.widget.Toast

import androidx.activity.result.PickVisualMediaRequest

import androidx.activity.result.contract.ActivityResultContracts

import androidx.appcompat.app.AppCompatActivity

import androidx.cardview.widget.CardView

import androidx.lifecycle.lifecycleScope

import com.google.android.material.appbar.MaterialToolbar

import com.google.android.material.button.MaterialButton

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext



class MainActivity : AppCompatActivity() {

    private var preferences: Preferences? = null

    private var hasReFlashableImage = false

    private val reFlashButton: CardView get() = findViewById(R.id.reflashButton)



    private val editLauncher = registerForActivityResult(

        ActivityResultContracts.StartActivityForResult(),

    ) { result ->

        if (result.resultCode == ImageEditActivity.RESULT_PICK_AGAIN ||
            result.resultCode == ImagePreviewActivity.RESULT_PICK_AGAIN
        ) {

            launchImagePicker()

        } else {

            checkReFlashAbility()

        }

    }



    private val pickVisualMediaLauncher = registerForActivityResult(

        ActivityResultContracts.PickVisualMedia(),

    ) { uri ->

        if (uri != null) {

            processPickedImage(uri)

        }

    }



    private val pickLegacyLauncher = registerForActivityResult(

        ActivityResultContracts.GetContent(),

    ) { uri ->

        if (uri != null) {

            processPickedImage(uri)

        }

    }



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        SystemBarUtils.applyStatusBarInset(findViewById(R.id.appBar))
        SystemBarUtils.applyNavigationBarInset(findViewById(R.id.mainScroll))

        preferences = Preferences(this)



        findViewById<MaterialToolbar>(R.id.main_toolbar).setOnMenuItemClickListener { item ->

            if (item.itemId == R.id.action_settings) {

                startActivity(Intent(this, SettingsActivity::class.java))

                true

            } else false

        }



        checkReFlashAbility()



        reFlashButton.setOnClickListener {

            if (hasReFlashableImage) {

                startActivity(Intent(this, NfcFlasher::class.java))

            } else {

                Toast.makeText(this, "No image to reflash yet.", Toast.LENGTH_SHORT).show()

            }

        }



        findViewById<MaterialButton>(R.id.cta_pick_image_file)

            .setOnClickListener { launchImagePicker() }



        findViewById<MaterialButton>(R.id.cta_new_text)

            .setOnClickListener { startActivity(Intent(this, TextEditor::class.java)) }



        findViewById<MaterialButton>(R.id.cta_now_playing)

            .setOnClickListener { pushNowPlayingAsync() }



        findViewById<MaterialButton>(R.id.cta_card_studio)

            .setOnClickListener { startActivity(Intent(this, CardStudioActivity::class.java)) }

    }



    override fun onResume() {

        super.onResume()

        checkReFlashAbility()

        updateStatusChips()

        preferences?.let { NfcHelper.promptEnableIfNeeded(this, it) }

    }



    private fun launchImagePicker() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            pickVisualMediaLauncher.launch(

                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),

            )

        } else {

            pickLegacyLauncher.launch("image/*")

        }

    }



    private fun processPickedImage(uri: Uri) {

        lifecycleScope.launch {

            try {

                val decoded = withContext(Dispatchers.IO) {

                    contentResolver.openInputStream(uri)?.use { stream ->

                        BitmapFactory.decodeStream(stream)

                    }

                }

                if (decoded == null) {

                    Toast.makeText(

                        this@MainActivity,

                        getString(R.string.crop_failed, "could not read image"),

                        Toast.LENGTH_LONG,

                    ).show()

                    return@launch

                }

                val software = BitmapUtils.toSoftwareBitmap(decoded) ?: decoded

                val bounded = BitmapUtils.downscaleToMax(software, 2048)

                withContext(Dispatchers.IO) {

                    openFileOutput(PickedSourceFilename, Context.MODE_PRIVATE).use { out ->

                        bounded.compress(Bitmap.CompressFormat.PNG, 100, out)

                    }

                }

                editLauncher.launch(Intent(this@MainActivity, ImageEditActivity::class.java))

            } catch (e: Exception) {

                Log.e("MainActivity", "Image pick failed", e)

                Toast.makeText(

                    this@MainActivity,

                    getString(R.string.crop_failed, e.message ?: "unknown"),

                    Toast.LENGTH_LONG,

                ).show()

            }

        }

    }



    private fun pushNowPlayingAsync() {

        if (!isNotificationListenerEnabled()) {

            Toast.makeText(this, R.string.now_playing_need_access, Toast.LENGTH_LONG).show()

            startActivity(Intent(this, SettingsActivity::class.java))

            return

        }

        val track = NowPlayingMonitor.readActiveTrack(this) ?: NowPlayingMonitor.latest

        if (track == null) {

            Toast.makeText(this, R.string.now_playing_no_track, Toast.LENGTH_LONG).show()

            return

        }



        val btn = findViewById<MaterialButton>(R.id.cta_now_playing)

        btn.isEnabled = false

        Toast.makeText(this, R.string.now_playing_resolving_art, Toast.LENGTH_SHORT).show()



        lifecycleScope.launch {

            try {

                val prefs = preferences!!

                val pixels = prefs.getScreenSizePixels()

                val artSize = maxOf(pixels.first, pixels.second).coerceIn(256, 512)

                val resolved = AlbumArtResolver.resolve(this@MainActivity, track, artSize)

                val card = EInkImageProcessor.renderNowPlayingCard(

                    pixels.first,

                    pixels.second,

                    track.title,

                    track.artist,

                    albumArt = resolved.bitmap,

                    mode = prefs.getColorMode(),

                )

                openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { out ->

                    card.compress(Bitmap.CompressFormat.PNG, 100, out)

                }

                val sourceLabel = artSourceLabel(resolved.source)

                Toast.makeText(

                    this@MainActivity,

                    getString(R.string.now_playing_art_source, sourceLabel),

                    Toast.LENGTH_SHORT,

                ).show()

                startActivity(Intent(this@MainActivity, ImagePreviewActivity::class.java))

            } catch (e: Exception) {

                Log.e("MainActivity", "Now playing failed", e)

                Toast.makeText(

                    this@MainActivity,

                    getString(R.string.now_playing_failed, e.message ?: "unknown"),

                    Toast.LENGTH_LONG,

                ).show()

            } finally {

                btn.isEnabled = true

            }

        }

    }



    private fun artSourceLabel(source: AlbumArtSource): String = when (source) {

        AlbumArtSource.SESSION_BITMAP, AlbumArtSource.SESSION_URI -> getString(R.string.art_source_local)

        AlbumArtSource.DISK_CACHE -> getString(R.string.art_source_cache)

        AlbumArtSource.MEDIA_STORE, AlbumArtSource.NOTIFICATION -> getString(R.string.art_source_device)

        AlbumArtSource.REMOTE_ITUNES, AlbumArtSource.REMOTE_DEEZER -> getString(R.string.art_source_remote)

        AlbumArtSource.ARTIST_PLACEHOLDER -> getString(R.string.art_source_placeholder)

    }



    private fun updateStatusChips() {

        val prefs = preferences ?: return

        findViewById<TextView>(R.id.chipDisplaySize).text =

            getString(R.string.status_display, prefs.getScreenSize())

        findViewById<TextView>(R.id.chipColorMode).text =

            getString(R.string.status_color, prefs.getColorMode().label)

        findViewById<TextView>(R.id.chipNfcStatus).text = when (NfcHelper.getRadioState(this)) {

            NfcRadioState.ENABLED -> getString(R.string.status_nfc_enabled)

            NfcRadioState.DISABLED -> getString(R.string.status_nfc_disabled)

            NfcRadioState.UNAVAILABLE -> getString(R.string.status_nfc_unavailable)

        }

    }



    private fun isNotificationListenerEnabled(): Boolean {

        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false

        val cn = ComponentName(this, MediaNotificationListener::class.java)

        return flat.split(":").any { TextUtils.equals(it, cn.flattenToString()) }

    }



    private fun checkReFlashAbility() {

        val lastFile = getFileStreamPath(GeneratedImageFilename)

        val preview = findViewById<ImageView>(R.id.reflashButtonImage)

        if (lastFile.exists()) {

            hasReFlashableImage = true

            reFlashButton.setCardBackgroundColor(getColor(R.color.sankara_surface_elevated))

            preview.setImageURI(null)

            preview.setImageURI(Uri.fromFile(lastFile))

        } else {

            hasReFlashableImage = false

            reFlashButton.setCardBackgroundColor(getColor(R.color.sankara_surface))

            preview.setImageDrawable(getDrawable(android.R.drawable.stat_sys_warning))

        }

    }

}


