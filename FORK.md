# LastWave Bypass — fork notes

Fork of [Clash-Projects/LastWave-Native](https://github.com/Clash-Projects/LastWave-Native) (GPL-3.0)
adding a **mixer bypass that works without a USB DAC**, in the spirit of UAPP's "HiRes Direct".

Package id `com.lastwave.app.bypass`, app name **LastWave Bypass**: installs next to the official app.

## What it does

`Settings › Audio › Output & Loudness › Internal direct output`

When on (it also turns Bit-Perfect on, like USB Exclusive does), the decoded PCM is written to an
**AAudio EXCLUSIVE stream** on the phone's own output (speaker, wired jack). Exclusive AAudio
streams are memory-mapped (MMAP): the app writes straight into the HAL buffer, so AudioFlinger's
mixer, resampler and software effects never see the samples. This is the only public, root-free
way to bypass the Android mixer on internal outputs.

Negotiation, per track (`InternalDirectOutput.configure`):

1. source rate + source format → samples enter the HAL unchanged;
2. source rate + the endpoint's native format → format changed in `DirectOutputStream::write`
   (reported as lossless only when every source bit fits, e.g. 24-bit → 32-bit);
3. the endpoint's native rate + format → resampled in-app with the existing libsoxr converter.

The stream is always opened with every Oboe conversion disabled and verified after open
(sharing mode, rate, format, channels), so what the signal-path page shows is the real wire format.
Any refusal falls back to the regular mixer path.

## What it cannot do

- **Bluetooth**: the codec (SBC/AAC/aptX/LDAC) re-encodes everything after the mixer; there is no
  MMAP path. The option is greyed out / reported as inactive on Bluetooth.
- **USB DACs**: handled by the upstream usbdevfs *USB Exclusive Output* (unchanged).
- **Vendor DSP after the HAL** (speaker protection, OEM effects running in the audio DSP) is out of
  Android's reach. The internal path is therefore reported as **DIRECT → HAL**, never as gold
  BIT-PERFECT (which stays reserved for verified USB exclusive output).
- Whether a given phone grants exclusive MMAP access on a given output is up to the vendor HAL.

## UI

- Quality badge in the player: `… · Bit-perfect` / `… · Direct` / `… · Mixer` (Mixer only when
  Bit-Perfect is on but not achieved). Tap or long-press opens the signal-path page.
- Signal-path page: new rows for the internal route, plus an **Internal output** section showing the
  route, the direct-output state (or the refusal reason) and what the exclusive endpoint accepts.
- `Software volume (USB DAC)`: forces PCM-scaled volume instead of the DAC's Feature Unit volume
  (the hardware level is parked at 0 dB so the two never stack). Default keeps upstream behaviour.

## Files

| Area | Files |
|---|---|
| Native exclusive stream | `app/src/main/cpp/DirectOutputStream.{h,cpp}`, `DirectOutputBridge.cpp` |
| Kotlin output | `playback/InternalDirectStream.kt`, `playback/InternalDirectOutput.kt`, `playback/usb/InternalDirectPrefs.kt` |
| Integration | `NativeProcessingAudioSink.kt`, `MusicPlayer.kt`, `ExclusiveUsbOutput.kt`, `SignalPath.kt` |
| UI | `SignalPathDialog.kt`, `PlayerHost.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsSearchIndex.kt` |
| Tests | `app/src/test/.../InternalDirectSignalPathTest.kt` |

## Known limitation of any build from source

Lossless addon requests are signed in native code with a secret that only the official CI build
contains, bound to the `com.lastwave.app` package and the official signing certificate. Source
builds (this one included) fall back to the public default signature; whether a given addon
server accepts it is up to that server.

## Logs

`adb logcat -s InternalDirect DirectOutputStream NativeAudioSink` shows the probe result, every
negotiation attempt and why the stream was (not) engaged.
