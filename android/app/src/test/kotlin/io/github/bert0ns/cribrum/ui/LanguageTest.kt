package io.github.bert0ns.cribrum.ui

import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.FailedFile
import io.github.bert0ns.cribrum.pipeline.ScanReport
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.CategorySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The two languages (docs/design.md §8): Italian from `values-it`, and the entry that opens the system's language page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LanguageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val nextStarted get() = shadowOf(compose.activity).nextStartedActivity

    @Test
    @Config(qualifiers = "it")
    fun `the home screen speaks Italian, with the plural forms`() {
        compose.setContent {
            CategoryList(listOf(CategorySummary(Category.BLURRY, 1, bytes = 1_000_000), CategorySummary(Category.DOCUMENT, 3, bytes = 3_000_000)), 0, {}, {})
        }

        compose.onNodeWithText("Sfocate").assertExists()
        compose.onNodeWithText("1 immagine da controllare · libera 1,0 MB").assertExists()
        compose.onNodeWithText("3 immagini da controllare · libera 3,0 MB").assertExists()
        compose.onAllNodesWithText("Blurry").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "it")
    fun `a threshold value uses the Italian decimal comma`() {
        compose.setContent { ThresholdsScreen(THRESHOLDS + (Category.BLURRY to 0.7f), emptyMap(), emptyMap(), { _, _ -> }, {}) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sfocate: 0,70"))
        compose.onNodeWithText("Sfocate: 0,70").assertExists()
    }

    @Test
    @Config(qualifiers = "it")
    fun `the scan status rows read naturally in Italian, with the category names`() {
        val coverage = listOf(Coverage("embedding", 1, analysed = 90, needed = 100), Coverage("blur", 1, analysed = 1, needed = 1), Coverage("faces", 2, analysed = 0, needed = 0))
        val report = ScanReport(1_700_000_000_000, unreadable = 1, failures = mapOf("embedding" to 2), files = listOf(FailedFile("embedding", "a.jpg")))
        compose.setContent { SettingsScreen(coverage = coverage, lastScan = report) }

        for (text in listOf("Stato della scansione", "1 file illeggibile", "2 errori nell'ultima scansione", "Embedding", "90 di 100 immagini analizzate • versione 1", "Sfocate", "1 di 1 immagine analizzata • versione 1", "Volti", "2 con errore nell'ultima scansione")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun `settings opens the system page of the app's language`() {
        compose.setContent { SettingsScreen() }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Language"))
        compose.onNodeWithText("Language").performClick()

        val started = nextStarted
        assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, started.action)
        assertEquals(Uri.parse("package:${compose.activity.packageName}"), started.data)
    }

    @Test
    @Config(sdk = [30])
    fun `settings hides the language entry below Android 13`() {
        compose.setContent { SettingsScreen() }

        compose.onAllNodesWithText("Language").assertCountEquals(0)
        assertNull(nextStarted)
    }
}
