package io.github.bert0ns.cribrum.ui

import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.FailedFile
import io.github.bert0ns.cribrum.pipeline.ScanReport
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import io.github.bert0ns.cribrum.trash.TrashedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

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
        compose.onNodeWithText("1 immagine da controllare · si possono liberare 1,0 MB").assertExists()
        compose.onNodeWithText("3 immagini da controllare · si possono liberare 3,0 MB").assertExists()
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

        for (text in listOf("Stato della scansione", "1 file illeggibile", "2 errori nell'ultima scansione", "Embedding", "90 di 100 immagini analizzate • versione 1", "Sfocate", "1 di 1 immagine analizzata • versione 1", "Volti", "2 non analizzate nell'ultima scansione")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    @Config(qualifiers = "it")
    fun `the file names of a scan read in Italian too`() {
        val report = ScanReport(1_700_000_000_000, unreadable = 1, failures = mapOf("metadata" to 1), files = listOf(FailedFile("metadata", "b.jpg")), unreadableFiles = listOf("foto.heic"))
        compose.setContent { SettingsScreen(coverage = emptyList(), lastScan = report) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("1 file illeggibile"))
        compose.onNodeWithText("1 file illeggibile").performClick()
        for (text in listOf("Tocca per mostrare o nascondere i nomi dei file.", "Illeggibile: foto.heic", "Metadati: b.jpg")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    @Config(qualifiers = "it")
    fun `the summary and the trash hint give the chosen retention in Italian`() {
        val image = ImageRef("/sdcard/DCIM/a.jpg", 1, 0)
        val page = listOf(ReviewGroup("a", listOf(ReviewItem(image, suggestTrash = true))))
        val trashedAt = Instant.now()
        val trashed = listOf(TrashedImage(image, trashedAt, File("/sdcard/.cribrum-trash/x/a.jpg"), trashedAt + Duration.ofDays(1)))
        var days by mutableIntStateOf(1)
        var showTrash by mutableStateOf(false)
        compose.setContent {
            if (showTrash) {
                TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}, retentionDays = days)
            } else {
                CategoryScreen(page, 1, setOf(image.path), false, {}, { _, _ -> }, {}, retentionDays = days)
            }
        }

        for ((count, unit) in listOf(1 to "1 giorno", 7 to "7 giorni")) {
            days = count
            showTrash = false
            compose.onNodeWithText("Cestina 1, tieni 0").performClick()
            compose.onNodeWithText("per $unit.", substring = true).assertExists()
            compose.onNodeWithText("Annulla").performClick()
            showTrash = true
            compose.onNodeWithText("$unit dopo essere finite nel cestino", substring = true).assertExists()
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

    @Test
    @Config(qualifiers = "it")
    fun `the scan status failure message reads in Italian`() {
        compose.setContent { SettingsScreen(coverage = emptyList(), lastScanFailed = true) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("L'ultimo tentativo di scansione non è riuscito."))
        compose.onNodeWithText("L'ultimo tentativo di scansione non è riuscito.").assertExists()
    }
}
