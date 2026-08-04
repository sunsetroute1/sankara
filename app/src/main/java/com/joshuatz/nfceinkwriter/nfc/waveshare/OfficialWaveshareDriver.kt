package com.joshuatz.nfceinkwriter.nfc.waveshare

import android.content.Context
import android.graphics.Bitmap
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.os.SystemClock
import android.util.Log
import com.joshuatz.nfceinkwriter.nfc.EInkFlashResult
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the bundled official Waveshare NFCTag transfer engine (dex from v2.1.2).
 * Matches stock-app timing: upload ~4s, then hold NFC 7+ minutes while the module refreshes.
 */
object OfficialWaveshareDriver {

    private const val TAG = "OfficialWaveshareDrv"
    /** Official [activity.a.m] sets IsoDep timeout to 1700 ms — do not override. */
    private const val OFFICIAL_ISODEP_TIMEOUT_MS = 1700
    /** Observed on S26 Ultra + 2.7" module: ~7m38s total; allow headroom. */
    private const val MAX_REFRESH_HOLD_MS = 600_000L
    private const val PROGRESS_POLL_MS = 100L
    /**
     * Healthy uploads report engine progress within ~3s. If k() stays at -1 this long,
     * v() is internally retrying a dead transfer (observed 12–63s zombie holds) — abort it.
     */
    private const val ENGINE_SILENT_ABORT_MS = 12_000L
    /**
     * Official k() parks at 99 while polling refresh. Do NOT close IsoDep during refresh —
     * cutting NFC mid-refresh garbles the panel (observed on S26 Ultra + 2.7" module).
     */
    private const val REFRESH_PHASE_PROGRESS = 90
    /** v()=0 failures are frequently transient (observed: fail at 19:42:18, clean success at
     * 19:42:25 on the same hold). Retry while the user is still holding instead of bailing.
     */
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 3_000L
    /**
     * Observed on S26 Ultra + 2.7": v() can return progress=100 in ~2.4s while the panel is still
     * repainting. Keep powering the module through a minimum hold so the e-ink refresh finishes.
     */
    private const val MIN_PHYSICAL_HOLD_MS = 12_000L
    /** Tag lost this early — the Android Tag handle is dead; only a lift + re-discovery helps. */
    private const val EARLY_TAG_LOSS_MS = 2_500L

    private val activeIsoDep = AtomicReference<IsoDep?>(null)

    /** User tapped Cancel, or UI needs to break a stuck refresh poll. */
    fun abortActiveTransfer() {
        try {
            activeIsoDep.get()?.close()
        } catch (_: Exception) {
        }
    }

    private class AttemptOutcome(
        val result: EInkFlashResult,
        val retryable: Boolean,
        /** Tag handle is dead — in-place retries are pointless, only re-discovery helps. */
        val handleDead: Boolean = false,
        /** v() failed within [EARLY_TAG_LOSS_MS] — same as handleDead for retry policy. */
        val earlyTagLoss: Boolean = false,
    )

    fun transferSync(
        context: Context,
        tag: Tag,
        bitmap: Bitmap,
        panelType: Int,
        password: String,
        progress: (Int) -> Unit,
        onRetry: (attempt: Int, maxAttempts: Int) -> Unit = { _, _ -> },
    ): EInkFlashResult {
        var lastResult: EInkFlashResult? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                Log.i(TAG, "Transfer attempt $attempt/$MAX_ATTEMPTS after ${RETRY_DELAY_MS}ms pause")
                onRetry(attempt, MAX_ATTEMPTS)
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return lastResult ?: EInkFlashResult(false, "Transfer interrupted", "Waveshare official")
                }
                progress(1)
            }
            val outcome = attemptTransfer(context, tag, bitmap, panelType, password, progress)
            if (outcome.result.success || !outcome.retryable) return outcome.result
            if (outcome.handleDead || outcome.earlyTagLoss) {
                // Stale tag handle (observed m()=-1 instantly after a stalled v()). Only a
                // fresh discovery can fix this — bail out so the flasher can auto re-arm.
                Log.i(TAG, "Tag handle dead — returning for re-discovery instead of in-place retry")
                return outcome.result
            }
            lastResult = outcome.result
        }
        val failed = lastResult ?: EInkFlashResult(false, "Transfer failed", "Waveshare official")
        return EInkFlashResult(
            false,
            "Module not responding after $MAX_ATTEMPTS attempts — it is likely still refreshing. " +
                "Lift the phone away, wait 60–90 seconds, then tap Try again.",
            failed.driverName,
            retryable = true,
        )
    }

    private fun attemptTransfer(
        context: Context,
        tag: Tag,
        bitmap: Bitmap,
        panelType: Int,
        password: String,
        progress: (Int) -> Unit,
    ): AttemptOutcome {
        val isoDep = IsoDep.get(tag)
            ?: return AttemptOutcome(
                EInkFlashResult(false, "IsoDep unavailable on this tag", "Waveshare official"),
                retryable = false,
            )

        var progressThread: Thread? = null
        val holdStartMs = SystemClock.elapsedRealtime()
        val refreshStalled = AtomicBoolean(false)
        val peakEngineProgress = AtomicInteger(0)
        activeIsoDep.set(isoDep)
        return try {
            val engine = OfficialWaveshareBridge.createEngine(context, panelType)
            val connect = try {
                OfficialWaveshareBridge.connectIsoDep(engine, isoDep)
            } catch (e: Exception) {
                val stale = e.cause?.message?.contains("out of date", ignoreCase = true) == true ||
                    e.message?.contains("out of date", ignoreCase = true) == true
                Log.w(TAG, "Official connect m() failed stale=$stale", e)
                return AttemptOutcome(
                    EInkFlashResult(
                        false,
                        if (stale) {
                            "NFC tag expired — lift the phone away briefly, then hold it back on the module."
                        } else {
                            e.message ?: "Could not connect to module"
                        },
                        "Waveshare official",
                        retryable = stale,
                    ),
                    retryable = stale,
                    handleDead = stale,
                )
            }
            Log.i(TAG, "Official connect m()=$connect timeout=${isoDep.timeout}ms")
            if (connect != 1) {
                return AttemptOutcome(
                    EInkFlashResult(
                        false,
                        "Could not connect to module (code $connect)",
                        "Waveshare official",
                        retryable = true,
                    ),
                    retryable = true,
                    handleDead = true,
                )
            }

            // Official engine expects 1700 ms per transceive (set by m()).
            if (isoDep.timeout != OFFICIAL_ISODEP_TIMEOUT_MS) {
                isoDep.timeout = OFFICIAL_ISODEP_TIMEOUT_MS
            }

            val pwdResult = OfficialWaveshareBridge.sendPassword(
                engine,
                password.toByteArray(StandardCharsets.US_ASCII),
            )
            Log.i(TAG, "Official password o()=$pwdResult")
            if (pwdResult == -1) {
                return AttemptOutcome(
                    EInkFlashResult(false, "Password rejected by module", "Waveshare official"),
                    retryable = false,
                )
            }

            progress(1)
            val uploadDone = AtomicBoolean(false)
            progressThread = Thread {
                var lastReported = 0
                var lastSeen = Int.MIN_VALUE
                var lastChangeMs = SystemClock.elapsedRealtime()
                while (!Thread.currentThread().isInterrupted) {
                    val now = SystemClock.elapsedRealtime()
                    val engineProgress = OfficialWaveshareBridge.progress(engine)
                    if (engineProgress in 1..100) {
                        peakEngineProgress.updateAndGet { maxOf(it, engineProgress) }
                    }
                    if (engineProgress != lastSeen) {
                        lastSeen = engineProgress
                        lastChangeMs = now
                    }
                    // Abort only during upload (progress < 90). During refresh, abort if k() is
                    // frozen at 99 for too long — logs showed 6+ minute zombie polls.
                    if (!uploadDone.get() &&
                        engineProgress in 0 until REFRESH_PHASE_PROGRESS &&
                        now - lastChangeMs > ENGINE_SILENT_ABORT_MS
                    ) {
                        Log.w(
                            TAG,
                            "Engine stalled at progress=$engineProgress for " +
                                "${now - lastChangeMs}ms during upload — closing IsoDep to abort v()",
                        )
                        try {
                            isoDep.close()
                        } catch (_: Exception) {
                        }
                        break
                    }
                    // Never close IsoDep during refresh — hold NFC until v() returns or user cancels.
                    if (engineProgress >= REFRESH_PHASE_PROGRESS &&
                        engineProgress < 100 &&
                        now - lastChangeMs > 15_000L
                    ) {
                        refreshStalled.set(true)
                    }
                    val reported = if (engineProgress in 1..100) {
                        engineProgress
                    } else {
                        // Keep the bar moving slightly, but never fake near-completion.
                        (1 + (now - holdStartMs) / 1_000L).toInt().coerceAtMost(15)
                    }
                    if (reported != lastReported) {
                        lastReported = reported
                        progress(reported)
                    }
                    try {
                        Thread.sleep(PROGRESS_POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.also { it.start() }

            // NOTE: official a.a() native dither needs RenderScript that doesn't init under
            // DexClassLoader (returns blank → panel clears). The r() encoder thresholds pixels
            // itself, so pass the already strict-monochrome bitmap straight through.
            Log.i(
                TAG,
                "Official transfer v() starting panel=$panelType bitmap=${bitmap.width}x${bitmap.height}",
            )
            val result = OfficialWaveshareBridge.transfer(engine, panelType, bitmap)
            uploadDone.set(true)
            val afterTransferProgress = OfficialWaveshareBridge.progress(engine)
            Log.i(
                TAG,
                "Official transfer v()=$result progress=$afterTransferProgress " +
                    "elapsed=${SystemClock.elapsedRealtime() - holdStartMs}ms",
            )

            // v() should block through refresh, but keep IsoDep open until progress hits 100
            // (observed official hold is 7+ minutes on Rev2.2).
            while (result == 1 && OfficialWaveshareBridge.progress(engine) < 100) {
                val elapsed = SystemClock.elapsedRealtime() - holdStartMs
                if (elapsed > MAX_REFRESH_HOLD_MS) {
                    Log.w(TAG, "Refresh hold timed out at progress=${OfficialWaveshareBridge.progress(engine)}")
                    break
                }
                val p = OfficialWaveshareBridge.progress(engine).coerceIn(1, 99)
                progress(p)
                Thread.sleep(PROGRESS_POLL_MS)
            }

            val finalProgress = OfficialWaveshareBridge.progress(engine)
            val totalMs = SystemClock.elapsedRealtime() - holdStartMs
            Log.i(
                TAG,
                "Official transfer done result=$result finalProgress=$finalProgress totalMs=$totalMs",
            )

            when {
                result == 1 && finalProgress >= 100 -> {
                    val fastReport = totalMs < MIN_PHYSICAL_HOLD_MS
                    if (fastReport) {
                        Log.i(
                            TAG,
                            "Engine reported 100% in ${totalMs}ms — holding NFC ${MIN_PHYSICAL_HOLD_MS - totalMs}ms " +
                                "more for physical refresh",
                        )
                        val holdUntil = holdStartMs + MIN_PHYSICAL_HOLD_MS
                        while (isoDep.isConnected && SystemClock.elapsedRealtime() < holdUntil) {
                            val p = OfficialWaveshareBridge.progress(engine).coerceIn(90, 100)
                            progress(p)
                            try {
                                Thread.sleep(PROGRESS_POLL_MS)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    val heldMs = SystemClock.elapsedRealtime() - holdStartMs
                    progress(100)
                    AttemptOutcome(
                        EInkFlashResult(
                            true,
                            "OK",
                            "Waveshare official",
                            needsPanelVerify = fastReport,
                        ),
                        retryable = false,
                    ).also {
                        Log.i(TAG, "Success after ${heldMs}ms total (fastReport=$fastReport)")
                    }
                }
                result == 1 -> {
                    progress(finalProgress.coerceIn(1, 99))
                    AttemptOutcome(
                        EInkFlashResult(
                            false,
                            if (finalProgress >= REFRESH_PHASE_PROGRESS) {
                                "Upload finished but refresh did not confirm — keep holding the phone " +
                                    "on the module until Done, or tap Clear panel and try again."
                            } else {
                                "Display refresh did not finish — keep holding the phone on the module longer and try again."
                            },
                            "Waveshare official",
                            refreshStalled = finalProgress >= REFRESH_PHASE_PROGRESS,
                            suppressAutoRearm = true,
                        ),
                        retryable = false,
                    )
                }
                result == 2 -> {
                    AttemptOutcome(
                        EInkFlashResult(
                            false,
                            "Incorrect image resolution for panel type $panelType",
                            "Waveshare official",
                        ),
                        retryable = false,
                    )
                }
                else -> {
                    val earlyLoss = totalMs < EARLY_TAG_LOSS_MS
                    val tagDead = earlyLoss || !isoDep.isConnected ||
                        (afterTransferProgress < 0 && totalMs > 5_000L)
                    val stalled = refreshStalled.get() ||
                        peakEngineProgress.get() >= REFRESH_PHASE_PROGRESS
                    AttemptOutcome(
                        EInkFlashResult(
                            false,
                            when {
                                earlyLoss ->
                                    "NFC link dropped too soon — lift the phone off the module for 2 seconds, " +
                                        "then hold it back firmly on the coil."
                                stalled && peakEngineProgress.get() >= REFRESH_PHASE_PROGRESS ->
                                    "Upload reached ${peakEngineProgress.get()}% but refresh was interrupted — " +
                                        "keep holding still for 15+ seconds, or tap Clear panel and try again."
                                tagDead ->
                                    "Transfer interrupted — partial image on panel. " +
                                        "Tap Clear panel, hold still until Done, wait 90s, then sync your image."
                                else ->
                                    "Module not responding — it is likely still refreshing from the last sync. " +
                                        "Wait 60–90 seconds, hold the phone flat on the coil, then tap Try again."
                            },
                            "Waveshare official",
                            retryable = !tagDead,
                            refreshStalled = stalled,
                            suppressAutoRearm = tagDead && totalMs > 60_000L,
                        ),
                        retryable = !tagDead,
                        handleDead = tagDead,
                        earlyTagLoss = earlyLoss,
                    )
                }
            }
        } catch (e: TagLostException) {
            Log.w(TAG, "Tag lost during official transfer", e)
            AttemptOutcome(
                EInkFlashResult(
                    false,
                    "Transfer interrupted — partial image on panel. " +
                        "Tap Clear panel, hold still until done, wait 90s, then sync your image.",
                    "Waveshare official",
                    retryable = false,
                    suppressAutoRearm = true,
                ),
                retryable = false,
                handleDead = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Official transfer error", e)
            AttemptOutcome(
                EInkFlashResult(false, e.message ?: "Official transfer failed", "Waveshare official"),
                retryable = false,
            )
        } finally {
            progressThread?.interrupt()
            activeIsoDep.compareAndSet(isoDep, null)
            try {
                if (isoDep.isConnected) isoDep.close()
            } catch (_: Exception) {
            }
        }
    }
}
