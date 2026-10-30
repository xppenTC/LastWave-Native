package com.lastwave.app.ui.feed

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.ui.settings.DEFAULT_YT_MUSIC_SECTIONS
import com.lastwave.app.ui.settings.YT_OTHER_SHELVES
import org.junit.Test

class YtShelfFilterTest {

    private val listedOnly = (DEFAULT_YT_MUSIC_SECTIONS - YT_OTHER_SHELVES).toSet()

    @Test
    fun frenchTitlesFollowTheirEnglishToggle() {
        assertThat(ytShelfHidden("Sélection rapide", setOf("Quick picks"))).isTrue()
        assertThat(ytShelfHidden("Playlists de la communauté", setOf("COMMUNITY PLAYLISTS"))).isTrue()
        assertThat(ytShelfHidden("Nouveautés", setOf("NEW RELEASES"))).isTrue()
        assertThat(ytShelfHidden("Titres tendances pour vous", setOf("Trending"))).isTrue()
        assertThat(ytShelfHidden("Sélection rapide", setOf("Trending"))).isFalse()
    }

    @Test
    fun unlistedShelvesFollowTheOtherToggle() {
        for (title in listOf("100% hits", "Besties Party", "Célébrez Halloween")) {
            assertThat(ytShelfHidden(title, listedOnly)).isFalse()
            assertThat(ytShelfHidden(title, listedOnly + YT_OTHER_SHELVES)).isTrue()
        }
        // A listed shelf stays visible when only "other" is unchecked.
        assertThat(ytShelfHidden("Sélection rapide", setOf(YT_OTHER_SHELVES))).isFalse()
    }
}
