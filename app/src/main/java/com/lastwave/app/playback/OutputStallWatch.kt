package com.lastwave.app.playback

/**
 * Flags an output whose clock stopped moving while it refuses more audio: the
 * device stopped consuming (rerouted when Android Auto was unplugged) without
 * reporting itself dead. Feed it on every refused write; reset it on a write
 * that lands or when playback pauses.
 */
class OutputStallWatch(private val limitMs: Long) {
    private var lastPositionUs = Long.MIN_VALUE
    private var sinceMs = -1L

    fun stalled(positionUs: Long, nowMs: Long): Boolean {
        if (sinceMs < 0 || positionUs != lastPositionUs) {
            lastPositionUs = positionUs
            sinceMs = nowMs
            return false
        }
        return nowMs - sinceMs >= limitMs
    }

    fun reset() {
        sinceMs = -1L
    }
}
