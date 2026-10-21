package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThresholdsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `each category shows its value and how many images it lists, and a raised one says it lists fewer`() {
        compose.setContent {
            ThresholdsScreen(
                thresholds = THRESHOLDS + (Category.BLURRY to 0.7f),
                counts = mapOf(Category.BLURRY to 10, Category.DOCUMENT to 1),
                defaultCounts = mapOf(Category.BLURRY to 25, Category.DOCUMENT to 1),
                onChange = { _, _ -> },
                onReset = {},
            )
        }

        compose.onNodeWithText("Raising a threshold takes images out of the lists", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Blurry: 0.70"))
        compose.onNodeWithText("Blurry: 0.70").assertIsDisplayed()
        compose.onNodeWithText("10 images to review").assertIsDisplayed()
        compose.onNodeWithText("15 images fewer than at the default").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Documents and notes: 0.20"))
        compose.onNodeWithText("Documents and notes: 0.20").assertIsDisplayed()
        compose.onNodeWithText("1 image to review").assertIsDisplayed()
    }

    @Test
    fun `moving a slider reports the new value once the finger lifts`() {
        val changes = mutableListOf<Pair<Category, Float>>()
        compose.setContent {
            ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { c, v -> changes += c to v }, {})
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Blurry"))
        compose.onNodeWithContentDescription("Blurry").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        assertEquals(listOf(Category.BLURRY to 0.75f), changes)
    }

    @Test
    fun `reset to defaults is offered`() {
        var resets = 0
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, { resets++ }) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("Reset to defaults").performClick()

        assertEquals(1, resets)
    }

    @Test
    fun `each step of a slider gives a tick, and reset gives a confirm`() {
        val given = mutableListOf<Haptic>()
        compose.setContent {
            CompositionLocalProvider(LocalHaptics provides { given += it }) {
                ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, {})
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Blurry"))
        compose.onNodeWithContentDescription("Blurry").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        assertEquals(listOf(Haptic.TICK), given)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("Reset to defaults").performClick()
        assertEquals(listOf(Haptic.TICK, Haptic.KEEP), given)
    }
}
