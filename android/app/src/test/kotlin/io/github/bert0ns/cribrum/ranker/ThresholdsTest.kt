package io.github.bert0ns.cribrum.ranker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThresholdsTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)

    @Test
    fun `without overrides the values are the ranker defaults`() {
        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test
    fun `an override is kept for the next start and leaves the other categories alone`() {
        Thresholds(preferences)[Category.BLURRY] = 0.75f

        assertEquals(THRESHOLDS + (Category.BLURRY to 0.75f), Thresholds(preferences).values)
    }

    @Test
    fun `setting the default value stores nothing, so a retuned default still reaches the user`() {
        Thresholds(preferences)[Category.BLURRY] = 0.75f
        Thresholds(preferences)[Category.BLURRY] = THRESHOLDS.getValue(Category.BLURRY)

        assertEquals(emptyMap<String, Any>(), preferences.all)
    }

    @Test
    fun `reset restores every default`() {
        Thresholds(preferences)[Category.BLURRY] = 0.75f
        Thresholds(preferences)[Category.DOCUMENT] = 0f
        Thresholds(preferences).reset()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold above 1 is refused`() {
        Thresholds(preferences)[Category.BLURRY] = 1.5f
    }

    @Test
    fun `a stored value outside 0 to 1 falls back to the default`() {
        preferences.edit().putFloat("threshold_BLURRY", 7f).commit()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }
}
