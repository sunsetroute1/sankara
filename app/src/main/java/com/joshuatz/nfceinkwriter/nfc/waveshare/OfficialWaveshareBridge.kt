package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.content.Context
import android.graphics.Bitmap
import android.nfc.tech.IsoDep
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Loads the official Waveshare NFCTag [activity.a] engine from bundled dex
 * (extracted from waveshare.feng.nfctag v2.1.2) and invokes the same entry
 * points the stock app uses: m(IsoDep), o(password), v(type, bitmap).
 */
object OfficialWaveshareBridge {

    private const val TAG = "OfficialWaveshare"
    private const val ASSET_DEX = "waveshare-official.dex"
    private const val ENGINE_CLASS = "waveshare.feng.nfctag.activity.a"

    @Volatile
    private var engineClass: Class<*>? = null

    private fun ensureLoaded(context: Context) {
        if (engineClass != null) return
        synchronized(this) {
            if (engineClass != null) return
            val appContext = context.applicationContext
            val dexOut = File(appContext.codeCacheDir, ASSET_DEX)
            // Android (S+) rejects loading a writable dex; copy fresh, then lock it read-only.
            if (dexOut.exists()) dexOut.delete()
            appContext.assets.open(ASSET_DEX).use { input ->
                dexOut.outputStream().use { output -> input.copyTo(output) }
            }
            dexOut.setWritable(false, false)
            dexOut.setReadOnly()
            val optimizedDir = File(appContext.codeCacheDir, "waveshare_odex").apply { mkdirs() }
            val loader = DexClassLoader(
                dexOut.absolutePath,
                optimizedDir.absolutePath,
                appContext.applicationInfo.nativeLibraryDir,
                appContext.classLoader,
            )
            engineClass = loader.loadClass(ENGINE_CLASS)
            Log.i(TAG, "Loaded official engine from ${dexOut.absolutePath} (canWrite=${dexOut.canWrite()})")
        }
    }

    fun createEngine(context: Context, panelType: Int): Any {
        ensureLoaded(context)
        val clazz = requireNotNull(engineClass)
        // The (Context, int) constructor is package-private — use getDeclaredConstructor.
        val ctor = clazz.getDeclaredConstructor(Context::class.java, Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        return ctor.newInstance(context.applicationContext, panelType)
    }

    /**
     * Official [activity.a.a] — native Floyd–Steinberg dither (RenderScript/libfsdither).
     * Stock app runs this before [transfer]; returns the processed bitmap, or the input on failure.
     */
    fun ditherImage(engine: Any, bitmap: Bitmap): Bitmap {
        return try {
            val method = engine.javaClass.getDeclaredMethod("a", Bitmap::class.java)
            method.isAccessible = true
            (method.invoke(engine, bitmap) as? Bitmap) ?: bitmap
        } catch (e: Exception) {
            Log.w(TAG, "dither a() failed: ${e.message}")
            bitmap
        }
    }

    fun connectIsoDep(engine: Any, isoDep: IsoDep): Int {
        val method = engine.javaClass.getMethod("m", IsoDep::class.java)
        return method.invoke(engine, isoDep) as Int
    }

    fun sendPassword(engine: Any, password: ByteArray): Int {
        val method = engine.javaClass.getMethod("o", ByteArray::class.java)
        return method.invoke(engine, password) as Int
    }

    /** Official MainActivity$g path — blocks until upload + refresh poll complete; returns 1 on success. */
    fun transfer(engine: Any, panelType: Int, bitmap: Bitmap): Int {
        val method = engine.javaClass.getMethod("v", Int::class.javaPrimitiveType, Bitmap::class.java)
        return method.invoke(engine, panelType, bitmap) as Int
    }

    /** Progress 0–100 via official [activity.a.k]. */
    fun progress(engine: Any): Int {
        return try {
            val method = engine.javaClass.getMethod("k")
            method.invoke(engine) as Int
        } catch (e: Exception) {
            Log.w(TAG, "progress k() failed: ${e.message}")
            0
        }
    }
}
