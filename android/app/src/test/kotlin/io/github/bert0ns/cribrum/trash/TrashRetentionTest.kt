package io.github.bert0ns.cribrum.trash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrashRetentionTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)

    @Test
    fun `the default is 30 days`() {
        assertEquals(30, TrashRetention(preferences).days)
        assertEquals(Duration.ofDays(30), TrashRetention(preferences).duration)
    }

    @Test
    fun `a choice is kept for the next start`() {
        TrashRetention(preferences).days = 7

        assertEquals(Duration.ofDays(7), TrashRetention(preferences).duration)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only the offered choices are accepted`() {
        TrashRetention(preferences).days = 5
    }

    @Test
    fun `a stored value that is not a choice falls back to the default`() {
        preferences.edit().putInt("trash_retention_days", 0).commit()

        assertEquals(30, TrashRetention(preferences).days)
    }
}
