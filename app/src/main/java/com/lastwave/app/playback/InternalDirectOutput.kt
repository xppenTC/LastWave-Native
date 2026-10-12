@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Where Android currently sends media audio, as far as this feature cares. */
enum class InternalRoute { SPEAKER, WIRED, BLUETOOTH, USB, OTHER, UNKNOWN }

/** Why the internal direct output is (un)available on the current route. */
enum class InternalDirectReason {
    AVAILABLE,
    ACTIVE,
    CHECKING,
    BLUETOOTH,
    USB,
    EXCLUSIVE_REFUSED,
    NO_AAUDIO,
    ANDROID_TOO_OLD,
    NATIVE_MISSING,
}

data class InternalDirectAvailability(
    val route: InternalRoute,
    val routeName: String,
    val reason: InternalDirectReason,
    val probe: InternalDirectStream.Probe? = null,
) {
    val available: Boolean
        get() = reason == InternalDirectReason.AVAILABLE || reason == InternalDirectReason.ACTIVE
}

/** The exclusive stream actually playing, for the signal-path page and the badge. */
data class InternalDirectSession(
    val route: InternalRoute,
    val routeName: String,
    val sourceRateHz: Int,
    val sourceEncoding: Int,
    val sourceBits: Int,
    val deviceRateHz: Int,
    val deviceEncoding: Int,
    val deviceChannels: Int,
    val resampled: Boolean,
    val formatConverted: Boolean,
    val formatLossless: Boolean,
    val mmap: Boolean,
    val deviceBufferFrames: Int,
)

/**
 * "Bypass the Android mixer without a USB DAC" (UAPP-style HiRes Direct).
 *
 * Opens an AAudio EXCLUSIVE stream on the phone's own output (speaker, wired
 * jack). Exclusive AAudio streams are memory-mapped (MMAP): the app writes into
 * the HAL buffer and AudioFlinger's mixer, resampler and effects never see the
 * samples. This is the only public, root-free way to do it. What it cannot do:
 *  - Bluetooth: the codec (SBC/AAC/LDAC…) re-encodes everything; no MMAP path.
 *  - USB DACs: handled by the existing usbdevfs exclusive output instead.
 *  - Vendor DSP after the HAL (speaker protection, OEM effects running in the
 *    audio DSP) is outside Android's reach, so this is reported as "direct to
 *    HAL", never as verified bit-perfect.
 *
 * Fails closed: every refusal leaves playback on the regular mixer path.
 */
@Singleton
class InternalDirectOutput @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val openLock = Any()

    @Volatile private var wanted = false
    @Volatile private var stream: InternalDirectStream? = null

    @Volatile private var activeSourceRateHz = 0
    @Volatile private var activeSourceEncoding = -1
    @Volatile private var activeSourceChannels = 0
    @Volatile private var deviceRateHz = 0
    @Volatile private var deviceEncoding = -1
    @Volatile private var deviceChannels = 0
    @Volatile private var deviceBufferFrames = 0

    @Volatile private var startMediaTimeUs = 0L
    @Volatile private var startNeedsInit = true
    @Volatile private var baseConsumedFrames = 0L
    @Volatile private var lastBufferedSeenMs = 0L
    @Volatile private var playing = false

    /** Per-route outcome cache: "srcRate/srcEnc" -> chosen (rate, encoding) or null when refused. */
    private val openCache = HashMap<String, Pair<Int, Int>?>()
    @Volatile private var cachedProbe: InternalDirectStream.Probe? = null
    private var probeJob: Job? = null

    private val _availability = MutableStateFlow(
        InternalDirectAvailability(InternalRoute.UNKNOWN, "", InternalDirectReason.CHECKING),
    )
    val availability: StateFlow<InternalDirectAvailability> = _availability.asStateFlow()

    private val _session = MutableStateFlow<InternalDirectSession?>(null)
    val session: StateFlow<InternalDirectSession?> = _session.asStateFlow()

    /** Last reason the stream could not engage while wanted (null once it engages). */
    @Volatile var lastFailure: InternalDirectReason? = null
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onRouteChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onRouteChanged()
    }

    init {
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }
        // Route only: the endpoint is probed lazily (settings screen open or
        // feature enabled), never at app start for users who keep it off.
        refreshAvailability(probe = false)
    }

    fun setWanted(enabled: Boolean) {
        if (wanted == enabled) return
        wanted = enabled
        if (!enabled) {
            synchronized(openLock) { closeLocked() }
            lastFailure = null
        }
        refreshAvailability()
    }

    fun isWanted(): Boolean = wanted

    fun isActive(): Boolean = _session.value != null && isAlive()

    fun hasOpenStream(): Boolean = stream != null

    fun isAlive(): Boolean = stream?.counters()?.getOrNull(3) == 1L

    /** Rate the stream consumes (after any app-side resampling). 0 when idle. */
    fun deviceRateHz(): Int = if (_session.value != null) deviceRateHz else 0

    fun requiresResampling(): Boolean = _session.value?.resampled == true

    fun underruns(): Long = stream?.counters()?.getOrNull(2) ?: 0L

    /** Current route as Android reports it for media playback. */
    fun currentRoute(): Pair<InternalRoute, String> {
        val manager = audioManager ?: return InternalRoute.UNKNOWN to ""
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                manager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()
            }.getOrNull()
        } else {
            null
        } ?: guessRoute(manager)
        if (device == null) return InternalRoute.UNKNOWN to ""
        return classify(device.type) to (device.productName?.toString().orEmpty())
    }

    /**
     * Re-evaluates the route and, when the route allows it, re-probes the
     * exclusive endpoint in the background (debounced). Never probes while
     * our own stream holds the endpoint.
     */
    fun refreshAvailability(
        probe: Boolean = wanted || _availability.subscriptionCount.value > 0,
    ) {
        val (route, name) = currentRoute()
        val early = when {
            !NATIVE_OK -> InternalDirectReason.NATIVE_MISSING
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> InternalDirectReason.ANDROID_TOO_OLD
            route == InternalRoute.BLUETOOTH -> InternalDirectReason.BLUETOOTH
            route == InternalRoute.USB -> InternalDirectReason.USB
            _session.value != null -> InternalDirectReason.ACTIVE
            else -> null
        }
        if (early != null) {
            _availability.value = InternalDirectAvailability(route, name, early, cachedProbe)
            return
        }
        cachedProbe?.let { probe ->
            _availability.value = InternalDirectAvailability(route, name, reasonFor(probe), probe)
            return
        }
        _availability.value = InternalDirectAvailability(route, name, InternalDirectReason.CHECKING)
        if (!probe) return
        probeJob?.cancel()
        probeJob = scope.launch {
            delay(PROBE_DEBOUNCE_MS)
            val result = synchronized(openLock) {
                if (_session.value != null) null else InternalDirectStream.probe(OUT_CHANNELS)
            }
            val (r, n) = currentRoute()
            if (result != null) cachedProbe = result
            _availability.value = when {
                _session.value != null -> InternalDirectAvailability(r, n, InternalDirectReason.ACTIVE, cachedProbe)
                r == InternalRoute.BLUETOOTH -> InternalDirectAvailability(r, n, InternalDirectReason.BLUETOOTH)
                r == InternalRoute.USB -> InternalDirectAvailability(r, n, InternalDirectReason.USB)
                result == null -> InternalDirectAvailability(r, n, InternalDirectReason.NATIVE_MISSING)
                else -> InternalDirectAvailability(r, n, reasonFor(result), result)
            }
        }
    }

    /**
     * Opens (or reuses) the exclusive stream for [format]. Order of attempts:
     * 1. source rate + source encoding (bit-exact into the HAL buffer);
     * 2. source rate + the endpoint's native encoding (format change only);
     * 3. the endpoint's native rate + encoding (the sink resamples with soxr).
     */
    fun configure(format: Format, bitDepthHint: Int?): Boolean {
        if (!wanted) return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.sampleRate <= 0 ||
            format.channelCount !in 1..2
        ) return false
        val srcEnc = encodingOf(format.pcmEncoding) ?: return false
        val (route, routeName) = currentRoute()
        when (route) {
            InternalRoute.BLUETOOTH -> return fail(InternalDirectReason.BLUETOOTH)
            InternalRoute.USB -> return fail(InternalDirectReason.USB)
            else -> Unit
        }
        if (!NATIVE_OK) return fail(InternalDirectReason.NATIVE_MISSING)
        synchronized(openLock) {
            val current = stream
            if (current != null && _session.value != null && current.counters()[3] == 1L &&
                activeSourceRateHz == format.sampleRate && activeSourceEncoding == srcEnc &&
                activeSourceChannels == format.channelCount
            ) {
                // Same wire configuration: keep the endpoint (no reopen gap).
                current.pause()
                current.flush()
                playing = false
                rebaseClock()
                lastFailure = null
                return true
            }
            closeLocked()
            val created = InternalDirectStream()
            if (!created.isAvailable) {
                created.close()
                return fail(InternalDirectReason.NATIVE_MISSING)
            }
            val key = "${format.sampleRate}/$srcEnc"
            val remembered = openCache[key]
            val chosen: Pair<Int, Int>? = when {
                // This route already refused this source format: don't retry every track.
                openCache.containsKey(key) && remembered == null -> null
                remembered != null &&
                    created.open(remembered.first, OUT_CHANNELS, remembered.second) == InternalDirectStream.OPEN_OK ->
                    remembered
                else -> negotiate(created, format.sampleRate, srcEnc).also { openCache[key] = it }
            }
            if (chosen == null) {
                created.close()
                val probe = cachedProbe
                return fail(
                    if (probe == null || probe.status != 0) InternalDirectReason.NO_AAUDIO
                    else InternalDirectReason.EXCLUSIVE_REFUSED,
                )
            }
            val info = created.info()
            stream = created
            activeSourceRateHz = format.sampleRate
            activeSourceEncoding = srcEnc
            activeSourceChannels = format.channelCount
            deviceRateHz = chosen.first
            deviceEncoding = chosen.second
            deviceChannels = info?.channelCount ?: OUT_CHANNELS
            deviceBufferFrames = info?.deviceBufferFrames ?: 0
            playing = false
            rebaseClock()
            val sourceBits = effectiveBits(srcEnc, bitDepthHint)
            val resampled = chosen.first != format.sampleRate
            // Resampled audio reaches the stream as Float32 from soxr.
            val wireInput = if (resampled) InternalDirectStream.ENC_FLOAT else srcEnc
            _session.value = InternalDirectSession(
                route = route,
                routeName = routeName,
                sourceRateHz = format.sampleRate,
                sourceEncoding = srcEnc,
                sourceBits = sourceBits,
                deviceRateHz = chosen.first,
                deviceEncoding = chosen.second,
                deviceChannels = deviceChannels,
                resampled = resampled,
                formatConverted = wireInput != chosen.second || format.channelCount != deviceChannels,
                formatLossless = !resampled && isLossless(srcEnc, sourceBits, chosen.second),
                mmap = info?.mmap == true,
                deviceBufferFrames = deviceBufferFrames,
            )
            lastFailure = null
            Log.i(
                TAG,
                "INTERNAL DIRECT engaged route=$route src=${format.sampleRate}Hz/" +
                    "${InternalDirectStream.labelOf(srcEnc)} -> device=${chosen.first}Hz/" +
                    "${InternalDirectStream.labelOf(chosen.second)} resampled=$resampled mmap=${info?.mmap}",
            )
        }
        refreshAvailability()
        return true
    }

    private fun negotiate(target: InternalDirectStream, srcRate: Int, srcEnc: Int): Pair<Int, Int>? {
        if (target.open(srcRate, OUT_CHANNELS, srcEnc) == InternalDirectStream.OPEN_OK) {
            return srcRate to srcEnc
        }
        val probe = InternalDirectStream.probe(OUT_CHANNELS)?.also { cachedProbe = it }
        if (probe == null || !probe.usable) return null
        if (probe.encoding != srcEnc &&
            target.open(srcRate, OUT_CHANNELS, probe.encoding) == InternalDirectStream.OPEN_OK
        ) {
            return srcRate to probe.encoding
        }
        if (probe.sampleRateHz != srcRate &&
            target.open(probe.sampleRateHz, OUT_CHANNELS, probe.encoding) == InternalDirectStream.OPEN_OK
        ) {
            return probe.sampleRateHz to probe.encoding
        }
        return null
    }

    /**
     * Writes interleaved PCM in [inputEncoding] (Media3 C.ENCODING_*). Advances
     * [buffer] by what was queued. True when the whole buffer was taken.
     */
    fun write(buffer: ByteBuffer, presentationTimeUs: Long, media3Encoding: Int, channels: Int): Boolean {
        val target = stream ?: return false
        if (!buffer.hasRemaining()) return true
        val enc = encodingOf(media3Encoding) ?: return false
        // Anchor the clock on the first frames actually accepted: right after a
        // flush the RT callback may still be dropping the old backlog.
        val anchorConsumed = if (startNeedsInit) target.counters()[0] else 0L
        val source = if (buffer.isDirect) buffer else directCopyOf(buffer)
        val before = source.position()
        val accepted = target.write(source, enc, channels)
        if (source !== buffer) {
            buffer.position(buffer.position() + (source.position() - before))
        }
        if (startNeedsInit && accepted > 0) {
            startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            baseConsumedFrames = anchorConsumed
            startNeedsInit = false
        }
        lastBufferedSeenMs = SystemClock.elapsedRealtime()
        return !buffer.hasRemaining()
    }

    fun play() {
        playing = true
        stream?.start()
    }

    fun pause() {
        playing = false
        stream?.pause()
    }

    /** Seek/stop: drop everything queued and re-anchor the media clock on the next write. */
    fun flush() {
        stream?.flush()
        rebaseClock()
    }

    fun getCurrentPositionUs(): Long {
        val target = stream ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (startNeedsInit || deviceRateHz <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val consumed = target.counters()[0]
        val played = (consumed - baseConsumedFrames - deviceBufferFrames).coerceAtLeast(0L)
        return startMediaTimeUs + played * C.MICROS_PER_SECOND / deviceRateHz
    }

    /** Ring still holds audio, or the device buffer is still draining its last frames. */
    fun hasPendingData(): Boolean {
        val target = stream ?: return false
        val buffered = target.counters()[1]
        if (buffered > 0L) {
            lastBufferedSeenMs = SystemClock.elapsedRealtime()
            return true
        }
        if (!playing || deviceRateHz <= 0) return false
        val drainMs = deviceBufferFrames * 1000L / deviceRateHz + DRAIN_MARGIN_MS
        return SystemClock.elapsedRealtime() - lastBufferedSeenMs < drainMs
    }

    /** Sink reset between items: keep the endpoint for a same-format next item. */
    fun prepareForNextItem() {
        stream?.pause()
        stream?.flush()
        playing = false
        rebaseClock()
    }

    fun reset() {
        synchronized(openLock) { closeLocked() }
        refreshAvailability()
    }

    private fun rebaseClock() {
        startNeedsInit = true
        startMediaTimeUs = 0L
        baseConsumedFrames = stream?.counters()?.getOrNull(0) ?: 0L
    }

    private fun closeLocked() {
        stream?.let {
            it.closeStream()
            it.close()
        }
        stream = null
        playing = false
        activeSourceRateHz = 0
        activeSourceEncoding = -1
        activeSourceChannels = 0
        deviceRateHz = 0
        deviceEncoding = -1
        deviceChannels = 0
        deviceBufferFrames = 0
        startNeedsInit = true
        _session.value = null
    }

    private fun fail(reason: InternalDirectReason): Boolean {
        lastFailure = reason
        Log.w(TAG, "INTERNAL DIRECT unavailable: $reason (mixer path kept)")
        return false
    }

    private fun onRouteChanged() {
        // A new route is a new endpoint: forget what the old one accepted.
        // Off the main thread: configure() may hold the lock while opening.
        scope.launch {
            synchronized(openLock) {
                openCache.clear()
                cachedProbe = null
            }
            refreshAvailability()
        }
    }

    private fun reasonFor(probe: InternalDirectStream.Probe): InternalDirectReason = when {
        probe.status != 0 -> InternalDirectReason.NO_AAUDIO
        !probe.exclusive -> InternalDirectReason.EXCLUSIVE_REFUSED
        else -> InternalDirectReason.AVAILABLE
    }

    private var scratch: ByteBuffer? = null

    private fun directCopyOf(buffer: ByteBuffer): ByteBuffer {
        val size = buffer.remaining()
        val existing = scratch
        val target = if (existing != null && existing.capacity() >= size) existing
        else ByteBuffer.allocateDirect(maxOf(size, 64 * 1024)).order(ByteOrder.nativeOrder()).also { scratch = it }
        target.clear()
        target.put(buffer.duplicate())
        target.flip()
        return target
    }

    companion object {
        private const val TAG = "InternalDirect"
        private const val OUT_CHANNELS = 2
        private const val PROBE_DEBOUNCE_MS = 600L
        private const val DRAIN_MARGIN_MS = 15L

        private val NATIVE_OK: Boolean get() = InternalDirectStream.nativeLoaded

        // Lazy: the pure helpers below must stay usable in JVM unit tests.
        private val MEDIA_ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }

        fun encodingOf(media3Encoding: Int): Int? = when (media3Encoding) {
            C.ENCODING_PCM_16BIT -> InternalDirectStream.ENC_I16
            C.ENCODING_PCM_24BIT -> InternalDirectStream.ENC_I24
            C.ENCODING_PCM_32BIT -> InternalDirectStream.ENC_I32
            C.ENCODING_PCM_FLOAT -> InternalDirectStream.ENC_FLOAT
            else -> null
        }

        /** Real resolution of the source: FFmpeg hands 24-bit FLAC over as 32-bit words. */
        fun effectiveBits(encoding: Int, hint: Int?): Int {
            val container = InternalDirectStream.bitsOf(encoding)
            if (encoding == InternalDirectStream.ENC_FLOAT) return 32
            return if (hint != null && hint in 1 until container) hint else container
        }

        /** True when [devEnc] carries every bit of a [srcBits]-bit [srcEnc] source. */
        fun isLossless(srcEnc: Int, srcBits: Int, devEnc: Int): Boolean = when {
            srcEnc == devEnc -> true
            srcEnc == InternalDirectStream.ENC_FLOAT -> false
            devEnc == InternalDirectStream.ENC_FLOAT -> srcBits <= 24
            else -> InternalDirectStream.bitsOf(devEnc) >= srcBits
        }

        fun classify(type: Int): InternalRoute = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> InternalRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> InternalRoute.USB
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> InternalRoute.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> InternalRoute.WIRED
            else -> InternalRoute.OTHER
        }

        /** Pre-Android 13 heuristic: media follows Bluetooth, then USB, then the jack. */
        private fun guessRoute(manager: AudioManager): AudioDeviceInfo? {
            val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrDefault(emptyList())
            val order = listOf(InternalRoute.BLUETOOTH, InternalRoute.USB, InternalRoute.WIRED, InternalRoute.SPEAKER)
            for (wantedRoute in order) {
                outputs.firstOrNull { classify(it.type) == wantedRoute && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?.let { return it }
            }
            return outputs.firstOrNull()
        }
    }
}
