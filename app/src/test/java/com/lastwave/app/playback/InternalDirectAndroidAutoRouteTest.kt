package com.lastwave.app.playback

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android Auto is detected from car mode, whatever device Android reports for media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class InternalDirectAndroidAutoRouteTest {

    @Test
    fun carModeIsTheAndroidAutoRoute() {
        val context = RuntimeEnvironment.getApplication()
        val output = InternalDirectOutput(context)
        assertThat(output.currentRoute().first).isNotEqualTo(InternalRoute.ANDROID_AUTO)

        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).enableCarMode(0)

        assertThat(output.currentRoute()).isEqualTo(InternalRoute.ANDROID_AUTO to "Android Auto")
    }
}
