package com.lastwave.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Wraps an upstream [DataSource] to issue chunked HTTP Range requests (~1 MB range chunks)
 * for YouTube / googlevideo media.
 *
 * This prevents YouTube's server-side bandwidth throttling on open-ended Range requests
 * and prevents HTTP 403 on client profiles (such as IOS) that strictly mandate bounded Range headers.
 */
class ChunkedRangeDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) : DataSource {

    private var currentDataSpec: DataSpec? = null
    private var currentPosition: Long = 0L
    private var requestedLength: Long = C.LENGTH_UNSET.toLong()
    private var bytesReadTotal: Long = 0L
    private var currentChunkRemaining: Long = 0L
    private var isCurrentChunkOpen: Boolean = false
    private var contentLength: Long = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        currentDataSpec = dataSpec
        currentPosition = dataSpec.position
        requestedLength = dataSpec.length
        bytesReadTotal = 0L
        contentLength = C.LENGTH_UNSET.toLong()

        val isYouTube = dataSpec.uri.host?.let { host ->
            host.contains("googlevideo.com") || host.contains("youtube.com")
        } == true

        if (!isYouTube || (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length <= chunkSize)) {
            // Non-YouTube requests or small requests pass through directly
            return openUpstream(dataSpec)
        }

        openNextChunk()
        return if (requestedLength != C.LENGTH_UNSET.toLong()) requestedLength else C.LENGTH_UNSET.toLong()
    }

    private fun openNextChunk() {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        val spec = currentDataSpec ?: return
        val chunkLength = if (requestedLength != C.LENGTH_UNSET.toLong()) {
            val remaining = requestedLength - bytesReadTotal
            if (remaining <= 0) return
            minOf(chunkSize, remaining)
        } else {
            chunkSize
        }

        val chunkSpec = spec.buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()

        val opened = openUpstream(chunkSpec)
        // "bytes a-b/total" tells where the file ends; a short chunk does too.
        contentLength = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()?.substringAfterLast('/')?.toLongOrNull()
            ?: if (opened != C.LENGTH_UNSET.toLong() && opened < chunkLength) currentPosition + opened else contentLength
        currentChunkRemaining = if (opened != C.LENGTH_UNSET.toLong()) opened else chunkLength
    }

    private fun openUpstream(dataSpec: DataSpec): Long {
        // DefaultDataSource assigns its active source before open() completes.
        // Even a failed open therefore needs close() before the next request.
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        isCurrentChunkOpen = true
        return try {
            upstream.open(dataSpec)
        } catch (error: Exception) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val spec = currentDataSpec ?: return C.RESULT_END_OF_INPUT

        val isYouTube = spec.uri.host?.let { host ->
            host.contains("googlevideo.com") || host.contains("youtube.com")
        } == true

        if (!isYouTube || (requestedLength != C.LENGTH_UNSET.toLong() && requestedLength <= chunkSize)) {
            return upstream.read(buffer, offset, length)
        }

        if (requestedLength != C.LENGTH_UNSET.toLong() && bytesReadTotal >= requestedLength) {
            return C.RESULT_END_OF_INPUT
        }

        if (currentChunkRemaining <= 0) {
            if (contentLength != C.LENGTH_UNSET.toLong() && currentPosition >= contentLength) {
                return C.RESULT_END_OF_INPUT
            }
            // A failed open must reach ExoPlayer, which retries while the buffer plays on;
            // reported as end of input it cut the stream at the 1 MB boundary (~1:04).
            openNextChunk()
            if (!isCurrentChunkOpen || currentChunkRemaining <= 0) {
                return C.RESULT_END_OF_INPUT
            }
        }

        val bytesToRead = minOf(length.toLong(), currentChunkRemaining).toInt()
        val bytesRead = upstream.read(buffer, offset, bytesToRead)
        // The response ended before its chunk did: that is the end of the file.
        // (Reopening here returned bytes it never counted, so the next chunk repeated them.)
        if (bytesRead == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT

        currentPosition += bytesRead
        bytesReadTotal += bytesRead
        currentChunkRemaining -= bytesRead
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
        currentDataSpec = null
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 1024 * 1024L // 1 MB chunk (within ~512 KB–2 MB)
    }
}
