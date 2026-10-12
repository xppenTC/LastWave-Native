package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Fork: verdict rules for the internal (no USB DAC) exclusive AAudio output. */
class InternalDirectSignalPathTest {

    private fun session(
        sourceRate: Int = 96_000,
        sourceEnc: Int = InternalDirectStream.ENC_I24,
        sourceBits: Int = 24,
        deviceRate: Int = 96_000,
        deviceEnc: Int = InternalDirectStream.ENC_I24,
        resampled: Boolean = false,
        formatConverted: Boolean = false,
        formatLossless: Boolean = true,
    ) = InternalDirectSession(
        route = InternalRoute.WIRED,
        routeName = "Headphones",
        sourceRateHz = sourceRate,
        sourceEncoding = sourceEnc,
        sourceBits = sourceBits,
        deviceRateHz = deviceRate,
        deviceEncoding = deviceEnc,
        deviceChannels = 2,
        resampled = resampled,
        formatConverted = formatConverted,
        formatLossless = formatLossless,
        mmap = true,
        deviceBufferFrames = 960,
    )

    private fun input(internal: InternalDirectSession?) = SignalPathInput(
        sourceLabel = "FLAC 24/96",
        sourceRateHz = 96_000,
        sourceBitDepth = 24,
        isLossless = true,
        appOutputRateHz = internal?.deviceRateHz ?: 96_000,
        platformMixerRateHz = 48_000,
        dspBypassEnabled = true,
        crossfadeMixing = false,
        speed = 1f,
        appVolume = 1f,
        systemVolume = 15,
        systemVolumeMax = 15,
        systemVolumeFixed = false,
        dac = null,
        routedToDac = false,
        routeVerified = false,
        driftPpm = null,
        glitchCount = 0,
        isPlaying = true,
        internalDirect = internal,
        internalDirectWanted = true,
        bypassRequested = true,
    )

    @Test
    fun exactInternalPathIsDirectToHalButNeverBitPerfect() {
        val report = evaluateSignalPath(input(session()))
        assertThat(report.internalDirectActive).isTrue()
        assertThat(report.directToHal).isTrue()
        // Vendor DSP after the HAL is unverifiable: gold stays USB-only.
        assertThat(report.bitPerfect).isFalse()
        assertThat(report.checks.all { it.passed }).isTrue()
    }

    @Test
    fun resampledInternalPathFailsResamplerAndFlagsBadge() {
        val report = evaluateSignalPath(
            input(session(deviceRate = 48_000, deviceEnc = InternalDirectStream.ENC_FLOAT, resampled = true)),
        )
        assertThat(report.directToHal).isFalse()
        assertThat(report.clockFallbackResampled).isTrue()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_resampler }.passed)
            .isFalse()
    }

    @Test
    fun truncatingFormatIsReportedAsLossy() {
        val report = evaluateSignalPath(
            input(session(deviceEnc = InternalDirectStream.ENC_I16, formatConverted = true, formatLossless = false)),
        )
        assertThat(report.directToHal).isFalse()
        assertThat(report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_output }.passed)
            .isFalse()
    }

    @Test
    fun attenuatedSystemVolumeIsNotDirectToHal() {
        val report = evaluateSignalPath(input(session()).copy(systemVolume = 7))
        assertThat(report.directToHal).isFalse()
    }

    @Test
    fun refusalShowsAsFailingRowWithoutInternalSession() {
        val report = evaluateSignalPath(
            input(null).copy(internalDirectFailure = InternalDirectReason.BLUETOOTH),
        )
        assertThat(report.internalDirectActive).isFalse()
        val row = report.checks.first { it.labelRes == com.lastwave.app.R.string.signal_label_internal }
        assertThat(row.passed).isFalse()
        assertThat(row.detailRes).isEqualTo(com.lastwave.app.R.string.internal_reason_bluetooth)
    }

    @Test
    fun losslessWideningRules() {
        val i16 = InternalDirectStream.ENC_I16
        val i24 = InternalDirectStream.ENC_I24
        val i32 = InternalDirectStream.ENC_I32
        val f32 = InternalDirectStream.ENC_FLOAT
        assertThat(InternalDirectOutput.isLossless(i16, 16, i24)).isTrue()
        assertThat(InternalDirectOutput.isLossless(i24, 24, f32)).isTrue()
        assertThat(InternalDirectOutput.isLossless(i24, 24, i16)).isFalse()
        // FFmpeg hands 24-bit FLAC over as 32-bit words: real depth decides.
        assertThat(InternalDirectOutput.isLossless(i32, 24, i24)).isTrue()
        assertThat(InternalDirectOutput.isLossless(i32, 32, f32)).isFalse()
        assertThat(InternalDirectOutput.isLossless(f32, 32, i32)).isFalse()
        assertThat(InternalDirectOutput.effectiveBits(i32, 24)).isEqualTo(24)
        assertThat(InternalDirectOutput.effectiveBits(i32, null)).isEqualTo(32)
        assertThat(InternalDirectOutput.effectiveBits(i16, 24)).isEqualTo(16)
    }

    @Test
    fun routeClassification() {
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
            .isEqualTo(InternalRoute.BLUETOOTH)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET))
            .isEqualTo(InternalRoute.BLUETOOTH)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_USB_HEADSET))
            .isEqualTo(InternalRoute.USB)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            .isEqualTo(InternalRoute.SPEAKER)
        assertThat(InternalDirectOutput.classify(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
            .isEqualTo(InternalRoute.WIRED)
    }
}
