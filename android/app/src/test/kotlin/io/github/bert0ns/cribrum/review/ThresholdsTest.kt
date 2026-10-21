package io.github.bert0ns.cribrum.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
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
        Thresholds(preferences)[Category.DOCUMENT] = Thresholds.MIN
        Thresholds(preferences).reset()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold above 1 is refused`() {
        Thresholds(preferences)[Category.BLURRY] = 1.5f
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a threshold of 0 is refused, since it would suggest the best copy of a group`() {
        Thresholds(preferences)[Category.BLURRY] = 0f
    }

    @Test
    fun `a stored value outside the range falls back to the default`() {
        preferences.edit().putFloat("threshold_BLURRY", 7f).putFloat("threshold_DOCUMENT", 0f).commit()

        assertEquals(THRESHOLDS, Thresholds(preferences).values)
    }
}
