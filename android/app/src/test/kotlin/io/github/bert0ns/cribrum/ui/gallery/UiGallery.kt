package io.github.bert0ns.cribrum.ui.gallery

import android.content.Context
import android.database.SQLException
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.App
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.MainActivity
import io.github.bert0ns.cribrum.Permissions
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.ui.CompressBatches
import io.github.bert0ns.cribrum.ui.CompressViewModel
import io.github.bert0ns.cribrum.ui.CribrumTheme
import io.github.bert0ns.cribrum.ui.MAX_COMPARED
import io.github.bert0ns.cribrum.ui.ReviewViewModel
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile

/**
 * Renders every screen and key state of the app to PNG files, for a review of the UI by someone who cannot hold the
 * phone (android/README.md, "UI gallery"). It drives the real [App] with the real view models over a drawn library,
 * so the bar, the top bar, the snackbar and the dialogs are the app's own. It runs only with `-PuiGallery`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi", application = CribrumApp::class)
class UiGallery(private val shot: Shot) {
    private var written = false

    /**
     * WorkManager's own thread can still use its database when Robolectric closes it, at the end of a scene, and the
     * error fails a scene whose image is already written. That one error is ignored then.
     */
    @get:Rule(order = 0)
    val workManagerTeardown = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    val closed = generateSequence(e) { it.cause }.any { cause ->
                        cause is SQLException &&
                            cause.message.orEmpty().contains("connection is closed") &&
                            cause.stackTrace.any { it.className.startsWith("androidx.work.") }
                    }
                    if (!written || !closed) throw e
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val folder = TemporaryFolder()

    @Test
    fun render() {
        assumeTrue("set -PuiGallery to write the gallery", outputDir != null)
        RuntimeEnvironment.setQualifiers(shot.variant.qualifiers)
        RuntimeEnvironment.setFontScale(shot.variant.fontScale)
        val app = ApplicationProvider.getApplicationContext<CribrumApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(GalleryWorkerFactory()).build())
        val gallery = Gallery(compose, app, GalleryWorld(app, folder.newFolder("sdcard")))
        try {
            prime(gallery)
            shot.scene.run(gallery)
            val bitmap = gallery.capture()
            val dir = checkNotNull(outputDir).also { it.mkdirs() }
            File(dir, shot.fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(File(dir, META).also { it.mkdirs() }, shot.fileName + ".txt").writeText(shot.entry())
            written = true
        } finally {
            gallery.close()
        }
    }

    /**
     * A picture of a window with a dialog over it is the first picture of a JVM, and the native graphics then abort the
     * JVM ("Class not found: java/nio/ShortBuffer"). A plain picture first sets them up, so a single image can be rendered alone.
     */
    private fun prime(gallery: Gallery) {
        gallery.launch { androidx.compose.material3.Text("Cribrum") }
        gallery.capture()
        gallery.close()
    }

    companion object {
        private const val META = ".meta"
        private val outputDir = System.getProperty("cribrum.gallery.dir")?.let(::File)?.also { dir ->
            require(dir.isAbsolute && dir.parentFile != null) { "-PuiGallery needs an absolute folder other than the root, not '$dir'" }
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = scenes.flatMap { scene -> scene.variants.map { arrayOf<Any>(Shot(scene, it)) } }

        /**
         * Starts a run from an empty gallery, so an image of a scene that was removed does not stay. The runner calls
         * this once for each image, so the folder remembers the start of the JVM it was cleared for.
         */
        @BeforeClass
        @JvmStatic
        fun clear() {
            val dir = outputDir ?: return
            val meta = File(dir, META).also { it.mkdirs() }
            val run = File(meta, "run")
            val started = ProcessHandle.current().info().startInstant().toString()
            if (run.exists() && run.readText() == started) return
            // Only the images an earlier run wrote, each named by its entry: nothing else in the folder is touched.
            meta.listFiles { file -> file.extension == "txt" }?.forEach { entry ->
                File(dir, entry.nameWithoutExtension).delete()
                entry.delete()
            }
            run.writeText(started)
        }

        @AfterClass
        @JvmStatic
        fun writeIndex() {
            val dir = outputDir?.also { it.mkdirs() } ?: return
            val entries = File(dir, META).listFiles { file -> file.extension == "txt" }?.sortedBy { it.name }?.map { it.readText() }.orEmpty()
            File(dir, "index.md").writeText(indexOf(entries))
        }
    }
}

/** What a test needs while it drives a scene: the rule, the app, the pretend phone, and the way to look at the screen. */
class Gallery(val compose: ComposeTestRule, val app: CribrumApp, val world: GalleryWorld) {
    private var scenario: ActivityScenario<*>? = null

    fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(id: Int, quantity: Int, vararg args: Any): String = app.resources.getQuantityString(id, quantity, *args)

    /** Opens the real app on [model], with [compress] for the Compress page. */
    fun app(
        model: ReviewViewModel = world.model(),
        compress: CompressSetup = CompressSetup(),
        notificationsGranted: Boolean = true,
    ): ReviewViewModel {
        launch(compress.owner(app, world)) { App(model, notificationsGranted) }
        return model
    }

    /** Runs [content] as the app's own activity does: the app's theme, edge to edge, and the view model store of this scene. */
    fun launch(owner: ViewModelStoreOwner? = null, content: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent {
                    CribrumTheme {
                        if (owner == null) content() else CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
                    }
                }
            }
        }
    }

    /** Starts the app's own activity, which asks for All files access until [allFilesAccess] says it is granted. */
    fun launchMainActivity(allFilesAccess: Boolean) {
        MainActivity.permissions = object : Permissions {
            override fun hasAllFilesAccess() = allFilesAccess

            override fun hasNotificationPermission(context: Context) = true
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    fun close() {
        scenario?.close()
        WorkManager.getInstance(app).cancelAllWork()
        // WorkManager's threads from this scene must end before Robolectric closes its database, or they fail the next scene.
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)
        MainActivity.resetPermissions()
    }

    /** Runs the main looper and the Compose clock until [condition] holds. */
    fun await(what: String, condition: () -> Boolean) {
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("never saw: $what")
    }

    fun awaitText(text: String, substring: Boolean = false) = await(text) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    fun awaitNoText(text: String) = await("no $text") { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    fun click(text: String) {
        awaitText(text)
        compose.onNodeWithText(text).performClick()
    }

    fun clickDescription(description: String) {
        await(description) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(description).performClick()
    }

    /** Scrolls the first list that scrolls until [text] is on screen. */
    fun scrollTo(text: String, substring: Boolean = false) {
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text, substring = substring))
    }

    /** Swipes the first list that scrolls, or the last one with [inDialog] (a dialog's window comes last), up until it stops, as a finger does. */
    fun scrollToEnd(inDialog: Boolean = false) {
        repeat(SWIPES) {
            val lists = compose.onAllNodes(hasScrollAction())
            lists[if (inDialog) lists.fetchSemanticsNodes().size - 1 else 0].performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 120) }
        }
    }

    /** The nodes whose description holds [part], such as the tiles of a grid. */
    fun described(part: String) = compose.onAllNodesWithContentDescription(part, substring = true)

    fun awaitTag(tag: String) = await(tag) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Lets Coil decode the thumbnails and the animations finish. Coil works off the main thread, and Compose does not wait for it. */
    fun settle(frames: Int = 24) {
        compose.mainClock.autoAdvance = false
        repeat(frames) {
            compose.mainClock.advanceTimeBy(64)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(40)
        }
    }

    /**
     * The screen as a user sees it. With a dialog, a sheet or a menu open there are several windows, and Compose then takes
     * the picture of the whole screen, as they lie over each other.
     */
    fun capture(): Bitmap {
        settle()
        try {
            return compose.onNode(isRoot()).captureToImage().asAndroidBitmap()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}

private const val SWIPES = 12

/** What the Compress page reads: the photos it lists, the batch it follows, and the pictures its summary compares. */
class CompressSetup(
    val photos: List<CompressibleImage> = emptyList(),
    val batches: CompressBatches = GalleryBatches(),
    val favorites: Set<String> = emptySet(),
    val retentionDays: Int = 30,
) {
    /** A store that hands the app's `viewModel()` call the Compress view model of this scene, with the batch and photos above. */
    fun owner(app: CribrumApp, world: GalleryWorld): ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CompressViewModel(
                    app,
                    batches,
                    photos = { photos },
                    favorites = { this@CompressSetup.favorites },
                    retentionDays = { retentionDays },
                    preview = { original, _, out ->
                        out.parentFile?.mkdirs()
                        out.writeBytes(GalleryPictures.degraded(original))
                        // The size a real copy has, so the summary's numbers agree with its estimate.
                        RandomAccessFile(out, "rw").use { it.setLength(original.length() * 4 / 10) }
                        null
                    },
                    previewFiles = List(MAX_COMPARED) { File(world.root, ".previews/out$it.jpg") },
                    discard = {},
                )
            }
        }
        override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty
    }
}
