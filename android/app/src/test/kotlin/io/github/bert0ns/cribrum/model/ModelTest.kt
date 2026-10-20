package io.github.bert0ns.cribrum.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `image ref of a file has its size and mtime in nanoseconds`() {
        val file = folder.newFile("a.jpg").apply { writeText("12345") }
        file.setLastModified(1_700_000_000_123)

        assertEquals(ImageRef(file.path, 5, 1_700_000_000_123_000_000), file.toImageRef())
    }

    @Test
    fun `image ref of a symlink describes the link, not its target`() {
        val target = folder.newFile("target.jpg").apply { writeText("12345") }
        val link = Files.createSymbolicLink(folder.root.toPath().resolve("link.jpg"), target.toPath()).toFile()

        assertEquals(link.path, link.toImageRef().path)
        assertNotEquals(5L, link.toImageRef().size)
    }

    @Test
    fun `score outside 0 to 1 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Signal("blur", 1, Category.BLURRY, 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Signal("blur", 1, Category.BLURRY, -0.1f)
        }
    }

    @Test
    fun `stored names are the lab's`() {
        assertEquals(
            listOf(
                "exact_duplicate", "near_duplicate", "burst", "blurry",
                "bad_exposure", "screenshot", "document", "forwarded", "sticker",
            ),
            Category.entries.map { it.value },
        )
        assertEquals(listOf("faces", "favorite", "album"), Protection.entries.map { it.value })
        assertEquals(listOf("unreviewed", "kept", "trashed"), ReviewState.entries.map { it.value })
    }

    @Test
    fun `kind is read back from its stored name`() {
        assertEquals(Category.SCREENSHOT, Kind.of("screenshot"))
        assertEquals(Protection.FACES, Kind.of("faces"))
        assertThrows(IllegalArgumentException::class.java) { Kind.of("sunset") }
    }

    @Test
    fun `identity ignores detector version and score`() {
        val evidence = mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value, "distance" to "3")
        val old = Signal("near_duplicate", 1, Category.NEAR_DUPLICATE, 0.6f, evidence)
        val new = Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.9f, evidence)

        assertEquals(old.identity, new.identity)
        assertEquals(SignalIdentity(Category.NEAR_DUPLICATE, "g", "inferior"), old.identity)
        assertEquals(SignalIdentity(Category.BLURRY, "", ""), Signal("blur", 1, Category.BLURRY, 0f).identity)
    }
}
