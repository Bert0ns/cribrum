package io.github.bert0ns.cribrum.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/** The app's build for this test: synthetic data, no storage access (app/src/nonMinifiedRelease). */
private const val TARGET = "io.github.bert0ns.cribrum.profile"
private const val SEED_CODE = "/cribrum/profile/"
private const val WAIT_MS = 15_000L
private const val SWIPE_STEPS = 10
private const val FLING_MS = 1_000L

/** The titles in English and Italian, which are the only things this test taps. A plain and a grouped category. */
private val CATEGORIES = listOf("Screenshots|Screenshot", "Similar copies|Copie simili")
private val BACK = Pattern.compile("Back|Indietro")

/**
 * Starts the app, waits for the home, opens a category and scrolls it. It must only start, open
 * screens and scroll, and never tap trash, keep, skip, undo, exclude or a setting.
 */
class HomeAndCategoriesGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** The first start fills the index. The rule clears the profile before it collects, so that code stays out of it. */
    @Before
    fun seed() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("am start -W -n $TARGET/io.github.bert0ns.cribrum.MainActivity")
    }

    @Test
    fun generate() = rule.collect(TARGET, maxIterations = 8, filterPredicate = { SEED_CODE !in it }) {
        pressHome()
        startActivityAndWait()
        CATEGORIES.forEach { open(it) }
    }

    private fun MacrobenchmarkScope.open(category: String) {
        val title = checkNotNull(device.wait(Until.findObject(By.text(Pattern.compile(category))), WAIT_MS)) { "no $category on the home" }
        title.click()
        check(device.wait(Until.hasObject(By.desc(BACK)), WAIT_MS)) { "$category did not open" }
        device.waitForIdle()
        val grid = checkNotNull(device.wait(Until.findObject(By.scrollable(true)), WAIT_MS)) { "$category has no grid" }
        val bounds = grid.visibleBounds
        val (top, bottom) = bounds.top + bounds.height() / 5 to bounds.bottom - bounds.height() / 5
        // Swipes fast enough to fling, not UiObject2.fling, which waits five seconds on each for a scroll event that never comes.
        repeat(3) { swipe(bounds.centerX(), bottom, top) }
        repeat(3) { swipe(bounds.centerX(), top, bottom) }
        device.pressBack()
    }

    private fun MacrobenchmarkScope.swipe(x: Int, from: Int, to: Int) {
        device.swipe(x, from, x, to, SWIPE_STEPS)
        Thread.sleep(FLING_MS)
    }
}
