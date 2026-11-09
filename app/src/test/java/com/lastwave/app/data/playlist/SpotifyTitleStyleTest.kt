package com.lastwave.app.data.playlist

import com.lastwave.app.data.music.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Spotify liked songs that found no YouTube Music match before. */
class SpotifyTitleStyleTest {

    private fun base(title: String) = TextMatch.baseTitle(youTubeStyleTitle(title)).trim()

    @Test
    fun `spotify dash tag matches the plain youtube title`() {
        assertEquals("Crazy", base("Crazy - Single Version"))
        assertEquals("Bailando", base("Bailando - Spanish Version"))
        assertEquals("Breakfast In America", base("Breakfast In America - Remastered 2010"))
    }

    @Test
    fun `spotify with credit is a featuring credit`() {
        assertEquals("i don't know", base("i don't know (with HUH YUNJIN of LE SSERAFIM)"))
        assertEquals("NEURON", base("NEURON (with Gaeko & YOON MIRAE)"))
    }

    @Test
    fun `live tag still refuses the studio take`() {
        val wanted = youTubeStyleTitle("Natural - Live")
        assertEquals("Natural (Live)", wanted)
        assertTrue(TextMatch.isSameTake("Natural (Live)", wanted))
        assertFalse(TextMatch.isSameTake("Natural", wanted))
    }

    @Test
    fun `titles without a dash tag are unchanged`() {
        assertEquals("Shape of You", youTubeStyleTitle("Shape of You"))
        assertEquals("Hey - You (Live)", youTubeStyleTitle("Hey - You - Live"))
        assertEquals("Song (Remix)", youTubeStyleTitle("Song (Remix)"))
    }
}
