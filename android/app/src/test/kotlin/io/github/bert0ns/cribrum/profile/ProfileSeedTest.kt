package io.github.bert0ns.cribrum.profile

import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.MainActivity
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.review.PAGE_SIZE
import io.github.bert0ns.cribrum.review.Review
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ProfileSeedTest {
    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()

    @After
    fun tearDown() {
        MainActivity.resetPermissions()
    }

    @Test
    fun `the home and the categories the generator opens have images to show`() = runBlocking {
        ProfileSeed().create(app)

        assertTrue(MainActivity.permissions.hasAllFilesAccess())
        assertTrue(MainActivity.permissions.hasNotificationPermission(app))
        val review = Review(app.index, app.trash)
        assertTrue(review.overview().freeableBytes > 0)
        for (category in listOf(Category.SCREENSHOT, Category.NEAR_DUPLICATE)) {
            val images = review.page(category).groups.flatMap { it.images }
            assertEquals(PAGE_SIZE, images.size)
            assertTrue(images.all { File(it.image.path).length() == it.image.size })
        }
    }

    @Test
    fun `a later start seeds nothing`() = runBlocking {
        ProfileSeed().create(app)
        val images = app.index.images()

        ProfileSeed().create(app)

        assertEquals(images, app.index.images())
    }
}
