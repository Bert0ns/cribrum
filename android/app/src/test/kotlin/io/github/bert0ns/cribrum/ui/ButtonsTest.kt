package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths
@Config(sdk = [36], qualifiers = "w360dp-h640dp")
class ButtonsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(first: String, second: String) = compose.setContent {
        Buttons(Modifier.fillMaxWidth()) {
            Button({}) { Text(first) }
            Button({}) { Text(second) }
        }
    }

    @Test
    fun `two buttons that fit stand side by side, the first taking the room left`() {
        show("Trash 45, keep 3", "Skip")

        val first = compose.onNodeWithText("Trash 45, keep 3").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Skip").fetchSemanticsNode().boundsInRoot
        assertEquals(first.center.y, second.center.y, 1f)
        assertTrue(first.right < second.left)
    }

    @Test
    fun `two buttons that do not fit stand one under the other, the first on top`() {
        show("Move 45 images to the trash and keep the other 3", "Skip this page for now")

        val first = compose.onNodeWithText("Move 45 images to the trash and keep the other 3").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Skip this page for now").fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top)
    }
}
