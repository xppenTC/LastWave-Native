package com.lastwave.app.data.addon

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class AddonClientHealthTest {
    private fun client(code: Int, body: String) = AddonClient(
        "https://addon.example.com",
        client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
    )

    @Test
    fun `a working addon reports its HTTP code with its name`() = runBlocking {
        val health = client(200, """{"name":"Test Addon","version":"2.1"}""").health()
        assertThat(health).isEqualTo(AddonHealth.Ok("HTTP 200 · Test Addon v2.1"))
    }

    @Test
    fun `a failing addon reports the HTTP code it answered`() = runBlocking {
        val health = client(404, "not found").health()
        assertThat(health).isInstanceOf(AddonHealth.Unreachable::class.java)
        assertThat((health as AddonHealth.Unreachable).reason).contains("HTTP 404")
    }
}
