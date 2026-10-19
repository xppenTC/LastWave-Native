# LastWave Bypass — fork notes

Fork of [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) (GPL-3.0)
adding a **mixer bypass that works without a USB DAC**, in the spirit of UAPP's "HiRes Direct".

Since v4.3.1 this fork is the official `main` (USB UAC1 fix, internal direct output and
Local songs are all merged upstream) plus fixes not merged yet and this package identity.

Package id `com.lastwave.app.bypass`, app name **LastWave Bypass**: installs next to the official app.

## What it does

`Settings › Audio › Output & Loudness › Internal direct output`

When on (it also turns Bit-Perfect on, like USB Exclusive does), the decoded PCM bypasses the
Android mixer on the phone's own outputs — speaker, jack **and Bluetooth** — through one of two
backends, tried in order for every track:

1. **Direct track (UAPP "HiRes Direct" recipe)** — a plain music `AudioTrack` in **24-bit packed**
   at the **source rate** (16-bit widened losslessly, 24-bit-in-32 narrowed losslessly). On
   Qualcomm-based phones the vendor audio policy places such a track on its `direct_pcm_out`
   profile (`AUDIO_OUTPUT_FLAG_DIRECT`, 44.1–384 kHz, speaker/jack/A2DP/BLE/USB). Apps cannot ask
   for that flag; the policy grants it. It is **verified per track**: Android refuses playback-rate
   changes on direct tracks and accepts them on mixed ones. A mixed track is dropped (no gain).
   Found by reading `dumpsys media.audio_flinger` while UAPP played on a HONOR Win: UAPP's 16-bit
   FLAC went out as 24-bit packed on a DIRECT thread to `BLUETOOTH_A2DP`.
2. **AAudio EXCLUSIVE (MMAP)** — fallback. Writes into the HAL buffer; usually fixed at 48 kHz, so
   44.1 kHz sources are resampled in-app (libsoxr). Opened with every Oboe conversion disabled and
   verified after open.

Any refusal falls back to the regular mixer path. A route change (jack, Bluetooth on/off) makes the
output re-negotiate and re-verify mid-track.

## What it cannot do

- **Bluetooth**: the mixer is bypassed, but the codec (SBC/AAC/aptX/LDAC) still re-encodes in the
  DSP afterwards. The signal-path page shows a failing "Bluetooth codec" row for that reason.
- **USB DACs**: handled by the upstream usbdevfs *USB Exclusive Output* (unchanged).
- **Vendor DSP after the HAL** (speaker protection, OEM effects running in the audio DSP) is out of
  Android's reach. The internal path is therefore reported as **DIRECT → HAL**, never as gold
  BIT-PERFECT (which stays reserved for verified USB exclusive output).
- Whether a phone exposes a direct profile at all is up to its vendor audio policy.

## UI

- Quality badge in the player: `… · Bit-perfect` / `… · Direct` (`Direct?` when Android did not
  confirm it) / `… · Mixer` (Mixer only when Bit-Perfect is on but not achieved). Tap or long-press opens the signal-path page.
- Signal-path page: new rows for the internal route, plus an **Internal output** section showing the
  route, the direct-output state (or the refusal reason) and what the exclusive endpoint accepts.
- `Software volume (USB DAC)`: forces PCM-scaled volume instead of the DAC's Feature Unit volume
  (the hardware level is parked at 0 dB so the two never stack). Default keeps upstream behaviour.

## Files

| Area | Files |
|---|---|
| Native exclusive stream | `app/src/main/cpp/DirectOutputStream.{h,cpp}`, `DirectOutputBridge.cpp` |
| Kotlin output | `playback/DirectTrackStream.kt`, `playback/InternalDirectStream.kt`, `playback/InternalDirectOutput.kt`, `playback/usb/InternalDirectPrefs.kt` |
| Integration | `NativeProcessingAudioSink.kt`, `MusicPlayer.kt`, `ExclusiveUsbOutput.kt`, `SignalPath.kt` |
| UI | `SignalPathDialog.kt`, `PlayerHost.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsSearchIndex.kt` |
| Tests | `app/src/test/.../InternalDirectSignalPathTest.kt` |

## Known limitation of any build from source

Lossless addon requests are signed in native code with a secret that only the official CI build
contains, bound to the `com.lastwave.app` package and the official signing certificate. Source
builds (this one included) fall back to the public default signature; whether a given addon
server accepts it is up to that server.

## Logs

`adb logcat -s InternalDirect DirectTrack DirectOutputStream NativeAudioSink` shows the probe result, every
negotiation attempt and why the stream was (not) engaged.
