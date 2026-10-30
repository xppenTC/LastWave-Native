package com.lastwave.app.playback

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lyrics.LyricLine
import org.junit.Test

class AndroidAutoNowPlayingTest {

    private val lines = listOf(
        LyricLine(timeMs = 1_000, text = "First line"),
        LyricLine(timeMs = 4_000, text = "  "),
        LyricLine(timeMs = 6_000, text = "Second line"),
    )

    @Test
    fun lyricFollowsThePosition() {
        assertThat(lyricLineAt(lines, 500)).isNull()
        assertThat(lyricLineAt(lines, 1_000)).isEqualTo("First line")
        assertThat(lyricLineAt(lines, 4_500)).isNull()
        assertThat(lyricLineAt(lines, 60_000)).isEqualTo("Second line")
        assertThat(lyricLineAt(emptyList(), 1_000)).isNull()
    }

    @Test
    fun subtitleShowsTheLyricElseArtistAndBadge() {
        assertThat(carSubtitle("Daft Punk", "24/96kHz", null)).isEqualTo("Daft Punk · 24/96kHz")
        assertThat(carSubtitle("Daft Punk", "AUDIO", null)).isEqualTo("Daft Punk")
        assertThat(carSubtitle("", "24/96kHz", null)).isEqualTo("24/96kHz")
        assertThat(carSubtitle("Daft Punk", "24/96kHz", "One more time")).isEqualTo("One more time")
    }
}
