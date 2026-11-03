package io.github.bert0ns.cribrum

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherIconTest {
    @Test
    fun `launcher icon inflates with foreground, background and monochrome layers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        assertNotNull(icon.background)
        assertNotNull(icon.monochrome)
    }
}
