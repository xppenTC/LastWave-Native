package com.lastwave.app.data.lossless

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.lossless.LosslessMusicApi.Companion.parseTitle
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Test

class LosslessAlbumEvidenceTest {
    private val api = LosslessMusicApi(OkHttpClient(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun overlap(wantedTitle: String, wantedAlbum: String, gotTitle: String, gotAlbum: String) =
        api.hasAlbumOrContextOverlap(parseTitle(wantedTitle), parseTitle(gotTitle), wantedAlbum, gotAlbum)

    @Test
    fun `a single named after the song is no evidence for another artist`() {
        // Tidal has no Sofiane original, only Djifason's same-length single of the same name.
        assertThat(overlap("Dingue de toi (Nabi-Nabilla)", "Dingue de toi (Nabi-Nabilla)", "Dingue de toi ", "Dingue de toi"))
            .isFalse()
    }

    @Test
    fun `a shared album name beyond the title still counts`() {
        assertThat(overlap("Arjan Vailly", "Animal", "Arjan Vailly", "Animal (Original Motion Picture Soundtrack)")).isTrue()
        assertThat(overlap("Song", "Night Drive", "Song", "Night Drive (Deluxe)")).isTrue()
    }
}
