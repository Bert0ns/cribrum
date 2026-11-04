package io.github.bert0ns.cribrum

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.ranker.GROUP_CATEGORIES
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.ui.AllFilesAccess
import io.github.bert0ns.cribrum.ui.CategoryList
import io.github.bert0ns.cribrum.ui.CategoryScreen
import io.github.bert0ns.cribrum.ui.CompressScreen
import io.github.bert0ns.cribrum.ui.CompressViewModel
import io.github.bert0ns.cribrum.ui.CribrumTheme
import io.github.bert0ns.cribrum.ui.Destination
import io.github.bert0ns.cribrum.ui.FavoriteRequestHost
import io.github.bert0ns.cribrum.ui.Folder
import io.github.bert0ns.cribrum.ui.Haptic
import io.github.bert0ns.cribrum.ui.ReviewOrderMenu
import io.github.bert0ns.cribrum.ui.ReviewViewModel
import io.github.bert0ns.cribrum.ui.ScanStatus
import io.github.bert0ns.cribrum.ui.Screen
import io.github.bert0ns.cribrum.ui.SettingsScreen
import io.github.bert0ns.cribrum.ui.StorageMapScreen
import io.github.bert0ns.cribrum.ui.ThresholdsScreen
import io.github.bert0ns.cribrum.ui.TrashScreen
import io.github.bert0ns.cribrum.ui.WithHaptics
import io.github.bert0ns.cribrum.ui.allFilesAccessSettings
import io.github.bert0ns.cribrum.ui.categoryName
import io.github.bert0ns.cribrum.ui.coversNavigation
import io.github.bert0ns.cribrum.ui.destination
import io.github.bert0ns.cribrum.ui.icon
import io.github.bert0ns.cribrum.ui.label
import io.github.bert0ns.cribrum.ui.readableWidth
import io.github.bert0ns.cribrum.ui.rememberHeat
import io.github.bert0ns.cribrum.ui.trailHue
import kotlinx.coroutines.launch
import java.io.File

interface Permissions {
    fun hasAllFilesAccess(): Boolean
    fun hasNotificationPermission(context: Context): Boolean
}

internal object DefaultPermissions : Permissions {
    override fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    override fun hasNotificationPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

class MainActivity : ComponentActivity() {
    val model: ReviewViewModel by viewModels()

    /** Checked again on every resume: the user grants it in the system settings, outside the app. */
    private var hasAllFilesAccess by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            hasAllFilesAccess = savedInstanceState.getBoolean(KEY_ALL_FILES_ACCESS, false)
        }
        enableEdgeToEdge()
        setContent {
            CribrumTheme {
                if (hasAllFilesAccess) {
                    App(
                        model = model,
                        notificationsGranted = notificationsGranted,
                        onNotificationResult = { notificationsGranted = it },
                    )
                } else {
                    Scaffold { padding ->
                        AllFilesAccess({ startActivity(allFilesAccessSettings(this)) }, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = permissions.hasAllFilesAccess()
        if (granted && !hasAllFilesAccess) {
            model.refreshAfterAccessGranted()
        }
        hasAllFilesAccess = granted
        notificationsGranted = permissions.hasNotificationPermission(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ALL_FILES_ACCESS, hasAllFilesAccess)
    }

    companion object {
        private const val KEY_ALL_FILES_ACCESS = "all_files_access"
        internal var permissions: Permissions = DefaultPermissions

        internal fun resetPermissions() {
            permissions = DefaultPermissions
        }
    }
}

@Composable
fun App(
    model: ReviewViewModel = viewModel(),
    notificationsGranted: Boolean = true,
    onNotificationResult: (Boolean) -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    WithHaptics(state.hapticsEnabled) {
        AppContent(
            model = model,
            notificationsGranted = notificationsGranted,
            onNotificationResult = onNotificationResult,
        )
    }
}

/**
 * What the snackbar after an applied page says. The size too: with no dialog before it, the snackbar is where the user
 * reads what the page freed, and the space in "190 MB" does not break. A page with nothing marked was only kept; one
 * whose moves all failed says that, and not "0 images kept".
 */
internal fun appliedMessage(context: Context, decision: Decision): String {
    val resources = context.resources
    val size = Formatter.formatShortFileSize(context, decision.trashed.sumOf { it.size }).replace(' ', '\u00A0')
    return listOfNotNull(
        decision.trashed.takeIf { it.isNotEmpty() }?.let { resources.getQuantityString(R.plurals.trashed_snackbar, it.size, it.size, size) },
        if (decision.trashed.isEmpty() && decision.failed.isEmpty()) resources.getQuantityString(R.plurals.kept_snackbar, decision.kept.size, decision.kept.size) else null,
        decision.failed.takeIf { it.isNotEmpty() }?.let { resources.getQuantityString(R.plurals.not_moved_snackbar, it.size, it.size) },
    ).joinToString(". ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(
    model: ReviewViewModel,
    notificationsGranted: Boolean,
    onNotificationResult: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by model.state.collectAsStateWithLifecycle()
    val compressModel: CompressViewModel = viewModel()
    val batch by compressModel.batches.state.collectAsStateWithLifecycle()
    val compressing = batch is BatchState.WaitingForScan || batch is BatchState.Running
    val progress by remember { Indexing.progress(context) }.collectAsStateWithLifecycle(null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotificationResult(granted)
    }
    FavoriteRequestHost()
    val app = context.applicationContext as CribrumApp
    // Opening the app starts no scan: the nightly one and "Scan now" do (design §4).
    LaunchedEffect(Unit) {
        Indexing.schedulePeriodic(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!notificationsGranted && !app.notificationPreferences.permissionRequested) {
                app.notificationPreferences.permissionRequested = true
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    // The list reloads when a scan starts or ends and at each phase, so the candidates of the early
    // detectors show up while the embeddings are still computed.
    LaunchedEffect(progress == null, progress?.step) {
        if (state.screen == Screen.Home) model.refresh()
        if (state.screen == Screen.Settings) model.reloadSettings() // the scan changed the embedding count
        if (state.screen == Screen.Thresholds) model.reloadThresholds()
    }
    val decision = state.lastDecision
    val trashedMessage = decision?.let { appliedMessage(context, it) }
    val undo = stringResource(R.string.undo)
    LaunchedEffect(decision) {
        if (decision == null || trashedMessage == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(trashedMessage, undo, withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            haptics.performHapticFeedback(Haptic.TICK.type)
            model.undo(decision)
        } else {
            model.dismissUndo(decision)
        }
    }
    val stayed = state.stayedInTrash
    val stayedMessage = if (stayed.isEmpty()) null else pluralStringResource(R.plurals.stayed_in_trash_snackbar, stayed.size, stayed.size)
    LaunchedEffect(stayed) {
        if (stayedMessage == null) return@LaunchedEffect
        snackbar.showSnackbar(stayedMessage, withDismissAction = true, duration = SnackbarDuration.Long)
        model.dismissStayedInTrash(stayed)
    }
    val changeFailed = state.changeFailed
    val changeFailedMessage = stringResource(R.string.change_failed_snackbar)
    LaunchedEffect(changeFailed) {
        if (!changeFailed) return@LaunchedEffect
        snackbar.showSnackbar(changeFailedMessage, withDismissAction = true, duration = SnackbarDuration.Long)
        model.dismissChangeFailed()
    }
    val favoriteAppeared = state.favoriteAppeared
    val favoriteAppearedMessage = stringResource(R.string.favorite_appeared_snackbar)
    LaunchedEffect(favoriteAppeared) {
        if (!favoriteAppeared) return@LaunchedEffect
        snackbar.showSnackbar(favoriteAppearedMessage, withDismissAction = true, duration = SnackbarDuration.Long)
        model.dismissFavoriteAppeared()
    }
    val exclusion = state.lastExclusion
    val excludedMessage = exclusion?.let { stringResource(R.string.excluded_folder_snackbar, File(it).name) }
    LaunchedEffect(exclusion) {
        if (exclusion == null || excludedMessage == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(excludedMessage, undo, withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            haptics.performHapticFeedback(Haptic.TICK.type)
            model.undoExclusion()
        } else {
            model.dismissExclusion(exclusion)
        }
    }
    BackHandler(state.screen != Screen.Home) { model.back() }
    val screen = state.screen
    val saveable = rememberSaveableStateHolder()
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { destination ->
                item(
                    selected = destination == screen.destination,
                    onClick = { model.selectDestination(destination) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(destination.label()) },
                )
            }
        },
        // Full screen views cover the bar. Hiding it keeps the bar's own choice of a bar or a rail otherwise.
        layoutType = if (screen.coversNavigation) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        },
    ) {
        Scaffold(
            // The category screen has its own host, above the trash button.
            snackbarHost = { if (screen !is Screen.Category) SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (screen) {
                                Screen.Home -> stringResource(R.string.app_name)
                                Screen.Compress -> stringResource(R.string.nav_compress)
                                is Screen.Category -> categoryName(screen.category)
                                is Screen.Trash -> stringResource(R.string.trash)
                                // The bar's own label, which the #322 fix keeps short enough for a font scale of 1.5.
                                Screen.Settings -> stringResource(R.string.nav_settings)
                                Screen.StorageMap -> stringResource(R.string.storage_map)
                                Screen.Thresholds -> stringResource(R.string.settings_thresholds)
                            },
                        )
                    },
                    navigationIcon = {
                        // The bar leads between destinations, so Back shows only where it goes one step up.
                        if (screen.coversNavigation) {
                            IconButton(model::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        }
                    },
                    actions = {
                        if (!screen.coversNavigation) {
                            IconButton(model::openTrash) { Icon(Icons.Default.Delete, stringResource(R.string.trash)) }
                        }
                        if (screen is Screen.Category) {
                            ReviewOrderMenu(state.reviewOrder, ReviewOrder.optionsFor(screen.category), model::setReviewOrder)
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val body: @Composable () -> Unit = {
                    when (screen) {
                        Screen.Home -> Column(Modifier.readableWidth()) {
                            ScanStatus(
                                progress,
                                onScan = { Indexing.scanNow(context) },
                                onStop = { scope.launch { Indexing.stop(context) } },
                                indexEmpty = state.indexEmpty,
                                heat = rememberHeat(running = progress != null),
                                compressing = compressing,
                                lastScanAt = state.lastScan?.finishedAt,
                            )
                            CategoryList(state.categories, state.freeableBytes, model::open)
                        }
                        is Screen.Category -> CategoryScreen(
                            state.page,
                            state.remaining,
                            state.marked,
                            screen.category in GROUP_CATEGORIES,
                            model::toggle,
                            model::applyPage,
                            model::skip,
                            snackbar = snackbar,
                            onExcludeFolder = model::excludeFolder,
                            reviewOrder = state.reviewOrder,
                            retentionDays = state.retentionDays,
                            loading = state.loading,
                        )
                        is Screen.Trash -> TrashScreen(
                            state.trashed,
                            state.selected,
                            model::select,
                            model::selectAll,
                            model::restoreSelected,
                            model::deleteFromTrash,
                        )
                        Screen.Settings -> SettingsScreen(
                            modifier = Modifier.readableWidth(),
                            onOpenStorageMap = model::openStorageMap,
                            onOpenThresholds = model::openThresholds,
                            retentionDays = state.retentionDays,
                            onRetentionChange = model::chooseRetention,
                            pendingRetention = state.pendingRetention,
                            onConfirmRetention = model::confirmRetention,
                            onCancelRetention = model::cancelRetention,
                            excludedFolders = state.excludedFolders,
                            onRemoveExcludedFolder = model::removeExcludedFolder,
                            embeddingCount = state.embeddingCount,
                            storage = state.storage,
                            scanning = progress != null,
                            onDeleteEmbeddings = model::deleteEmbeddings,
                            hapticsEnabled = state.hapticsEnabled,
                            onHapticsChange = model::setHaptics,
                            coverage = state.coverage,
                            lastScan = state.lastScan,
                            lastScanFailed = state.lastScanFailed,
                        )
                        Screen.Thresholds -> ThresholdsScreen(
                            state.thresholds,
                            state.thresholdCounts,
                            state.defaultCounts,
                            model::setThreshold,
                            model::resetThresholds,
                            Modifier.readableWidth(),
                        )
                        Screen.StorageMap -> StorageMapScreen(
                            currentFolder = state.storageMap.lastOrNull() ?: Folder.EMPTY,
                            baseHue = trailHue(state.storageMap),
                            onZoomIn = model::zoomStorageMap,
                            onExclude = { model.excludeFolder(it.path) },
                            atRoot = state.storageMap.size <= 1,
                        )
                        Screen.Compress -> CompressScreen(compressModel, progress)
                    }
                }
                // Each destination keeps its scroll positions while the user is elsewhere.
                if (screen.coversNavigation) body() else saveable.SaveableStateProvider(screen.destination.name, body)
            }
        }
    }
}
