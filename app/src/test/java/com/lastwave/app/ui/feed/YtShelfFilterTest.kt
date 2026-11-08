package com.lastwave.app.ui.feed

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YtShelfFilterTest {

    @Test
    fun frenchTitlesFollowTheirEnglishToggle() {
        assertThat(ytShelfHidden("Sélection rapide", setOf("Quick picks"))).isTrue()
        assertThat(ytShelfHidden("Playlists de la communauté", setOf("COMMUNITY PLAYLISTS"))).isTrue()
        assertThat(ytShelfHidden("Nouveautés", setOf("NEW RELEASES"))).isTrue()
        assertThat(ytShelfHidden("Titres tendances pour vous", setOf("Trending"))).isTrue()
        assertThat(ytShelfHidden("Sélection rapide", setOf("Trending"))).isFalse()
    }
}
