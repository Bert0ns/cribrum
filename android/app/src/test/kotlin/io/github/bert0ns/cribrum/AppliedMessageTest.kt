package io.github.bert0ns.cribrum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.review.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppliedMessageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val images = (1..3).map { ImageRef("/sdcard/$it.jpg", 1_000_000, 0) }

    @Test
    fun `a trashed page says how many and their size, which does not break`() {
        val message = appliedMessage(context, Decision(trashed = images.take(2), kept = images.drop(2), failed = emptyList()))

        assertEquals("2 images moved to the trash, 2.0 MB", message)
    }

    @Test
    fun `a page with nothing marked was kept`() {
        assertEquals("3 images kept", appliedMessage(context, Decision(trashed = emptyList(), kept = images, failed = emptyList())))
    }

    @Test
    fun `a page whose moves all failed says so, and not that it kept nothing`() {
        val message = appliedMessage(context, Decision(trashed = emptyList(), kept = emptyList(), failed = images))

        assertEquals("3 images changed since the scan and stayed where they were", message)
    }

    @Test
    fun `a page with some failed moves says both`() {
        val message = appliedMessage(context, Decision(trashed = images.take(1), kept = emptyList(), failed = images.drop(1)))

        assertEquals("1 image moved to the trash, 1.0 MB. 2 images changed since the scan and stayed where they were", message)
    }
}
