package com.lastwave.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtistHelperTest {
    @Test
    fun `french play counts are not artists`() {
        assertThat(ArtistHelper.isPlayCountOrStat("44 k lectures")).isTrue()
        assertThat(ArtistHelper.isPlayCountOrStat("1,2 M de vues")).isTrue()
        assertThat(ArtistHelper.isPlayCountOrStat("Antenora")).isFalse()
        assertThat(ArtistHelper.isPlayCountOrStat("21 Savage")).isFalse()
    }
}
