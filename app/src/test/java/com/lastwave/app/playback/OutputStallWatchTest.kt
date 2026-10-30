package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutputStallWatchTest {

    @Test
    fun frozenClockIsStalledAfterTheLimit() {
        val watch = OutputStallWatch(limitMs = 3_000)
        assertThat(watch.stalled(positionUs = 500, nowMs = 0)).isFalse()
        assertThat(watch.stalled(positionUs = 500, nowMs = 2_999)).isFalse()
        assertThat(watch.stalled(positionUs = 500, nowMs = 3_000)).isTrue()
    }

    @Test
    fun movingClockOrResetIsNeverStalled() {
        val watch = OutputStallWatch(limitMs = 3_000)
        watch.stalled(positionUs = 0, nowMs = 0)
        assertThat(watch.stalled(positionUs = 10, nowMs = 4_000)).isFalse()
        assertThat(watch.stalled(positionUs = 10, nowMs = 6_000)).isFalse()
        watch.reset()
        assertThat(watch.stalled(positionUs = 10, nowMs = 9_000)).isFalse()
    }
}
