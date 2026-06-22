# Official Waveshare transfer binary

Sankara bundles the **official Waveshare NFCTag v2.1.2** transfer engine so NFC behaves
identically to the stock app, while keeping Sankara UI (now playing, editors, settings).

## Bundled artifacts

| Source | Destination | Purpose |
|--------|-------------|---------|
| `official-nfctag.apk` → `classes.dex` | `app/src/main/assets/waveshare-official.dex` | Official `activity.a` engine (DexClassLoader) |
| `official-nfctag.apk` → `lib/arm64-v8a/*.so` | `app/src/main/jniLibs/arm64-v8a/` | Image filter / dither natives used by official encode path |

## Refresh from a new official APK

```powershell
$apk = "path\to\official-nfctag.apk"
$root = "app\src\main"
Copy-Item "path\to\extracted\classes.dex" "$root\assets\waveshare-official.dex" -Force
# Extract arm64 .so files (exclude frida gadget) into $root\jniLibs\arm64-v8a\
```

## Code path

- `OfficialWaveshareBridge` — loads dex, calls `m(IsoDep)`, `o(password)`, `v(type, bitmap)`
- `OfficialWaveshareDriver` — sync transfer used from `NfcFlasher`
- Sankara addons unchanged: image prep, now playing, crop, settings, NFC UX

The legacy `Rev22*.kt` reimplementation remains in the tree as reference but is no longer used for transfer.
