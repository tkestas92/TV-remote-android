# TV Remote — project brief for AI coding agents

Native Android app (Pixel 9) that controls a TCL Android TV over Wi-Fi, with more
features than the Google Home remote. Work phase by phase; do not start the next
phase until the current one builds and runs on the phone.

## Verified facts about the target TV (tested 2026-09-24)

| Item | Value |
|---|---|
| Model | TCL BeyondTV, Realtek SoC (platform V8-R851T02) |
| OS | Android TV 9 (API 28), security patch 2020-09 |
| LAN IP | 192.168.0.61 (make it configurable, never hardcode) |
| ADB | Network ADB on port 5555 works; first connect needs "Allow" on TV |
| `adb shell input keyevent` | **~2.5 s per call — too slow, never use for buttons** |
| App launch via `monkey -p <pkg> -c android.intent.category.LEANBACK_LAUNCHER 1` | works but **slow (~2.7–3.3 s even on a warm ADB connection)** — monkey starts a Java VM. Prefer `am start -n <pkg>/<activity>` with the activity resolved once via `cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER <pkg>` and cached |
| YouTube deep link `am start -a android.intent.action.VIEW -d <url> com.google.android.youtube.tv` | works |
| scrcpy 4.1 mirroring | works incl. YouTube video, 1280x720; no audio (Android < 11) |
| Google Home app remote | works fast → Android TV Remote protocol v2 is available |
| Remote protocol ports | 6466 + 6467 open; pairing and command session work from the phone |
| `RemoteAppLinkLaunchRequest` | **not supported** — TV closes the :6466 session within ~25 ms. Keep `supportsAppLinks=false` |
| App launch from the phone | via ADB (dadb), warm connection, persisted RSA key (TV asked "Allow" once) |
| targetSdk 37 (Android 17) | LAN connections time out without the `ACCESS_LOCAL_NETWORK` runtime permission |
| Android background | LAN sockets are aborted when the app is backgrounded → stop session on ON_STOP, reconnect on ON_START |
| TCL picture menu (contrast etc.) | `am start -n com.tcl.settings/.MainActivity` (no physical key needed) |
| Direct contrast API | **not possible without root.** Chain: TTvPictureManager → TvVideoApi.setVideoCfg(CFG_TYPE_CONTRAST) → libcom_tcl_tv_jni.so → `/dev/sbinder`; SELinux denies the shell domain (`avc: denied { map } … sbinder_device`). No Android setting, no binder service (ITclTvService has 3 methods, ITVKitService none for picture). → Use the TCL settings intent below instead. |
| **Set a picture value (WORKS)** | `am force-stop com.tcl.settings` then `am startservice -n com.tcl.settings/.ShowWindowService -a com.tcl.settings.SHOW_WINDOW --es Type Settings --es ITEM contrast --es MOTION SET --es MOTION_VALUE 70` → TV shows its own "Contrast 70" slider and applies it. This is TCL's voice-assistant path (ShowWindowService → SeekBarLayoutDialog.setIntentMotion). MOTION can also be `UP`/`DOWN` (relative, MOTION_VALUE = step). The force-stop is needed, otherwise an already-open settings dialog is re-shown and the extras are ignored. |
| Other ITEM names (same mechanism, from setting_config.json) | `brightness` (backlight — **verified working, this is the main slider the user wants**), `color_saturation`, `sharpness`, `hue`, `gamma`, `pic_black_level`, `soundbalance`, `digital_audio_output_delay` |
| Standby | TV stays on Wi-Fi, :6466 reachable; Power key via Remote protocol wakes it |
| TV Wi-Fi MAC | 44:d8:78:15:c8:f2 (configurable in Settings) |
| Reconnect (implemented) | connect timeout 3 s, TLS 5 s, WoL magic packet before every attempt, fixed 2 s retry while foreground, restart on ON_START / Wi-Fi onAvailable, 12 s without any message → reconnect. Short-standby test: connect ok in 0.2–0.6 s on attempt 1. **Long standby (30+ min) not yet verified.** |
| ADB with 2 devices | always target the TV explicitly: `adb -s 192.168.0.61:5555 ...` |
| In-app mirror (scrcpy 4.1) | works at 1280x720; starts in ~5 s |
| Video encoders on the TV (`list_encoders=true`, verified) | **only software**: `OMX.google.h264.encoder`, `OMX.google.vp8.encoder`, `OMX.google.vp9.encoder`. No hardware encoder is exposed. Do not run list_encoders at runtime (costs ~3 s); always use h264 with low settings. |
| Mirror resolution | with the software encoder use sizes where **both width and height are multiples of 16** (e.g. `max_size=768` → 768x432, or 1024 → 1024x576). With `max_size=854` the server died with "Aborted" (suspected encoder alignment crash) |
| Server start command (WORKS) | `export CLASSPATH=/data/local/tmp/scrcpy-server.jar; app_process / com.genymobile.scrcpy.Server 4.1 scid=<scid> tunnel_forward=true audio=false control=true max_size=768 video_bit_rate=2500000 max_fps=24 video_codec=h264`. Kill old servers in a **separate** shell call with `pkill -f 'scrcpy-serve[r]'` (a plain `pkill -f scrcpy-server` in the same command kills the command itself). Skip the jar push if the size matches. |
| scrcpy control socket | **every write must be off the Main thread** (NetworkOnMainThreadException otherwise, touches silently lost) |

App packages:

- YouTube: `com.google.android.youtube.tv`
- Go3: `lt.go3.android.tv`
- Stremio: `com.stremio.one`
- Netflix: `com.netflix.ninja`

## Architecture

```
Pixel 9 app (Kotlin, Jetpack Compose)
 ├─ RemoteProtocolClient  → TV :6467 pairing / :6466 commands (TLS + protobuf)
 │     fast keys (~20–50 ms), power on/off, app links
 ├─ AdbClient (dadb)      → TV :5555
 │     launch apps, deep links, read state (dumpsys), text input fallback
 └─ ScrcpyClient (phase 3) → scrcpy-server on TV via ADB
       fast touch/key injection + H.264 mirror decoded with MediaCodec
```

Rules:

- **Buttons always go through the Remote protocol** (or scrcpy control channel), never `adb shell input`.
- ADB is for infrequent actions only (launch, deep link, state).
- Keep one long-lived connection per channel; reconnect with backoff; show connection state in UI.
- All network I/O on coroutines (Dispatchers.IO); UI never blocks.
- Haptic feedback on every button press so the UI feels instant.

## Tech stack

- Kotlin, Jetpack Compose, Material 3, single-activity
- Coroutines + Flow, simple MVVM (ViewModel + StateFlow); Hilt optional
- `dev.mobile:dadb` for ADB over TCP (store the RSA adbkey in app-private storage)
- Protobuf-lite (`com.google.protobuf:protobuf-kotlin-lite`) for Remote protocol messages
- DataStore for settings (TV IP, paired cert, shortcuts)
- minSdk 29, targetSdk latest

## Status (2026-10-22)

- **Phase 1: DONE.** Pairing, remote session (button cluster and D-pad; a stored `swipe` value reads as D-pad), power, Wake-on-LAN, auto-reconnect, app shortcuts via ADB `am start` with a cached component. `supportsAppLinks` stays false unless a host is marked otherwise.
- **Settings: DONE.** One English Settings screen (IP, pair, shortcuts, control mode, picture sliders, media-card toggle, MAC). Pairing starts only from the Pair button. The old "Nustatymai" / "TV ryšys" split is gone.
- **Phase 2: PARTIAL.** Keyboard (Remote IME, ASCII `input text` fallback) and Share → TV are done. Now playing is a notification and lock-screen media session, not an in-app card. Scenes, sleep timer, Quick Settings tile, and home-screen widget are not implemented.
- **Picture sliders: DONE.** Brightness (on by default), contrast, saturation, and sharpness through the TCL `ShowWindowService` intent.
- **Phase 3: DONE.** In-app scrcpy 4.1 mirror with touch, fullscreen, Smooth/Light, and auto-Light while video plays. Audio is off. `video=false` is not implemented.
- **Launcher: DONE.** HTTP to the Kantrybės TV launcher on port 8765 (favorites, hidden apps, wallpaper, launch, icons). No service discovery; uses the saved TV IP. The demo flavor does not call it.
- **Demo flavor: DONE.** No sockets. Virtual TV for the remote, a fake mirror, share, and the media card.
- **Not started:** Phase 4. NSD discovery of `_androidtvremote2._tcp` is not implemented. Long standby (30+ min) is still unverified.

## Phase 1 — MVP remote

1. **Settings screen**: TV IP input + "Test connection". Optional: NSD discovery of `_androidtvremote2._tcp`.
2. **Pairing (Remote protocol v2)**:
   - Generate a self-signed client cert (RSA 2048) once, store it in the Android Keystore/app storage.
   - TLS to TV:6467 → PairingRequest → Options (hex encoding, 6 symbols) → Configuration → TV shows a code.
   - User types the code; send PairingSecret = SHA-256(client modulus, client exponent, server modulus, server exponent, bytes of code[2:]). The first code byte must equal hash[0] (use it to validate typos).
   - Reference implementation for message formats and the hash: Python `androidtvremote2` (tronikos) — port its `.proto` files as-is.
3. **Remote session**: TLS to TV:6466 with the paired cert. Handle RemoteConfigure / RemoteSetActive, **answer every RemotePingRequest** (otherwise the TV drops the connection), send RemoteKeyInject(keycode, direction SHORT / START_LONG / END_LONG).
4. **Remote UI**:
   - D-pad + OK, Back, Home, Volume ±, Mute, Power, Play/Pause.
   - Touchpad area: swipe → D-pad steps (repeat while held), tap → OK.
   - Long-press support via START_LONG / END_LONG.
5. **App shortcuts**: grid of YouTube / Go3 / Stremio / Netflix launched via ADB `monkey` command (or RemoteAppLinkLaunchRequest where it works). Shortcuts are user-editable (package name + label + icon).

Done when: pair once, restart the app, buttons respond instantly, and all 4 shortcuts open the apps.

## Phase 2 — convenience

- **Share → TV**: intent-filter for `ACTION_SEND` text/plain; if the text is a YouTube URL → open it on the TV via ADB deep link. Toast result, finish activity immediately.
- **Text input**: keyboard sheet; send via the Remote protocol IME messages. Fallback: ADB `input text` (ASCII only; Lithuanian letters need ADBKeyboard IME later).
- **Now playing card**: poll `dumpsys media_session` every few seconds via ADB (only while app is foreground); show title/app + play/pause.
- **Scenes**: one button → sequence of actions (e.g. "Movie night": launch Stremio, set volume). Stored as JSON in DataStore.
- **Sleep timer**: turn TV off after N minutes (foreground service or WorkManager).
- Quick Settings tile + home-screen widget with the most used buttons.

## Phase 3 — screen on phone (Steam Link style)

- Push the scrcpy-server jar (bundled in assets, **version must match the protocol you implement**, e.g. scrcpy 4.1) to `/data/local/tmp/` via dadb.
- Start it with `app_process` (`tunnel_forward=true`, `audio=false`, `max_size=1280`), open the video and control sockets through the ADB forward to `localabstract:scrcpy_<scid>`.
- Decode H.264 with MediaCodec onto a SurfaceView; map taps/drags on the view to scrcpy touch events.
- Also usable with `video=false` as a fast control-only channel.
- Check the exact message layouts against the scrcpy source of the bundled version (`doc/develop.md`, `ControlMessage.java`).
- DRM apps (Netflix, Go3 streams) may show black; that is expected.

## Phase 4 — later ideas

- MCP server / AI agent ("play the latest Boiler Room set on the TV") — needs an always-on home server.
- Screen-time stats per app.
- Guest access via QR code on the TV.

## Conventions

- Package: `lt.kantrybes.tvremote`
- Modules/packages: `data/remote`, `data/adb`, `data/scrcpy`, `ui/remote`, `ui/settings`, `ui/shortcuts`
- No secrets or IPs in git. Unit-test protocol encoding (pairing hash, message framing).
- Build & install on the phone over wireless debugging: `./gradlew installDebug`.
