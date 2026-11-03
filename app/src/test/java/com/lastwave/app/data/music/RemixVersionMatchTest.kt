package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lossless.LosslessMusicApi
import org.junit.Test

class RemixVersionMatchTest {
    private val wanted = "All the Things She Said (Noiseflow & Triple X Remix)"

    private fun yt(title: String) = YouTubeMusicTrack(
        videoId = title.hashCode().toString(),
        title = title,
        artist = "DJ Gollum, Scarlet",
        album = "Single",
        artworkUrl = "https://lh3.googleusercontent.com/a",
    )

    @Test
    fun `the named remix beats another remix and the original on YouTube`() {
        val right = TextMatch.matchScore(yt(wanted), wanted, "DJ Gollum, Scarlet")
        val otherRemix = TextMatch.matchScore(yt("All the Things She Said (DJ Gollum Remix)"), wanted, "DJ Gollum, Scarlet")
        val original = TextMatch.matchScore(yt("All the Things She Said"), wanted, "DJ Gollum, Scarlet")
        assertThat(right).isGreaterThan(otherRemix)
        assertThat(right).isGreaterThan(original)
        assertThat(TextMatch.versionNames(wanted)).containsAtLeast("noiseflow", "triple")
        assertThat(TextMatch.versionNames("Song (Official Video)")).isEmpty()
    }

    @Test
    fun `lossless versions carry the remixer so another remix is rejected`() {
        val want = LosslessMusicApi.parseTitle(wanted).versions
        assertThat(want).isEqualTo(LosslessMusicApi.parseTitle("All The Things She Said (Noiseflow & Triple X Remix)").versions)
        assertThat(want).isNotEqualTo(LosslessMusicApi.parseTitle("All the Things She Said (Hands Up Remix)").versions)
        assertThat(LosslessMusicApi.parseTitle("Song (Extended Mix)").versions).containsExactly("extended", "mix")
    }
}
