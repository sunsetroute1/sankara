# Sankara

Android app for passive NFC e-ink displays — **Thomas Sankara–inspired** UI, built for the Waveshare 2.7" module (264×176 B/W).

Based on [joshuatz/nfc-epaper-writer](https://github.com/joshuatz/nfc-epaper-writer).

## Additions over upstream

- Default display: **2.7"** (264×176, SDK type 6)
- **Samsung NFC timeout** raised to 30s (stock SDK uses ~1.2s)
- **Floyd–Steinberg B/W dithering** on gallery images
- **Now playing** card (track + artist) via `MediaSessionManager` + notification listener
- Screen kept on during NFC sync

## Build & install (ADB)

```bash
cd eink-case-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in **Android Studio** → Run on device.

## First-run setup

1. Enable **NFC** on the phone.
2. Tap **Enable Now Playing Access** → allow **E-Ink Case** in notification listener settings (for track detection).
3. Confirm toolbar shows **2.7"** (gear icon to change if needed).

## Push an image

1. **Load Image** → crop → auto-dither → hold phone on module when prompted.
2. **Now Playing → E-Ink** → start music → tap button → hold phone on module.

## Debug NFC on Samsung

```bash
adb logcat -s NfcFlasher WaveShareHandler
```

## Hardware

| Item | Spec |
|------|------|
| Module | Waveshare 2.7" NFC-Powered e-Paper (B08B3RG439) |
| Resolution | 264 × 176, black/white |
| SDK type | 6 |
| Sync time | ~15–30 seconds, phone upper-back against module coil |

## License

Upstream NFC E-Ink Writer is MIT. Waveshare `NFC.jar` is vendor-provided; see [Waveshare wiki](https://www.waveshare.com/wiki/Android_SDK_for_NFC-Powered_e-Paper).
