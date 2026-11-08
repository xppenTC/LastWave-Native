package com.lastwave.app.data.playlist

import com.lastwave.app.data.spotify.SpotifyAuthRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyLikedPagingTest {
    private val total = 1203
    private var rateLimitedOnce = false

    /** Fake Pathfinder: `total` liked songs, 50 a page, one 429 on the way. */
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val offset = Regex(""""offset":(\d+)""")
            .find(request.url.queryParameter("variables").orEmpty())!!.groupValues[1].toInt()
        val (code, body) = if (offset == 500 && !rateLimitedOnce) {
            rateLimitedOnce = true
            429 to ""
        } else {
            val items = (offset until minOf(offset + 50, total)).joinToString(",") { i ->
                """{"track":{"data":{"name":"Song $i","artists":{"items":[{"profile":{"name":"Artist"}}]}}}}"""
            }
            200 to """{"data":{"me":{"library":{"tracks":{"totalCount":$total,"items":[$items]}}}}}"""
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val auth = mockk<SpotifyAuthRepository> {
        coEvery { getPersonalToken() } returns "token"
        coEvery { getClientToken() } returns null
    }

    @Test
    fun readsEveryLikedSongPastTheOldThousandCap() = runBlocking {
        val rows = SpotifyPlaylistImporter(client, mockk(), auth).fetchLikedSongs().rows
        assertEquals(total, rows.size)
        assertEquals("Song ${total - 1}", rows.last().title)
    }
}
