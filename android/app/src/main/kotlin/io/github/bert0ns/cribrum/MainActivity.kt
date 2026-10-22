package io.github.bert0ns.cribrum

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.ranker.GROUP_CATEGORIES
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.ui.AllFilesAccess
import io.github.bert0ns.cribrum.ui.CategoryList
import io.github.bert0ns.cribrum.ui.CategoryScreen
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
import io.github.bert0ns.cribrum.ui.trailHue
import java.io.File

class MainActivity : ComponentActivity() {
    /** Checked again on every resume: the user grants it in the system settings, outside the app. */
    private var hasAllFilesAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                if (hasAllFilesAccess) {
                    App()
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
        hasAllFilesAccess = Environment.isExternalStorageManager()
    }
}

@Composable
fun App(model: ReviewViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    WithHaptics(state.hapticsEnabled) { AppContent(model) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(model: ReviewViewModel) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by model.state.collectAsStateWithLifecycle()
    val progress by remember { Indexing.progress(context) }.collectAsStateWithLifecycle(null)
    val snackbar = remember { SnackbarHostState() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Opening the app starts no scan: the nightly one and "Scan now" do (design §4).
    LaunchedEffect(Unit) {
        Indexing.schedulePeriodic(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS) // the scan's progress notification
        }
    }
    // The list reloads when a scan starts or ends and at each phase, so the candidates of the early
    // detectors show up while the embeddings are still computed.
    LaunchedEffect(progress == null, progress?.step) {
        if (state.screen == Screen.Home) model.refresh()
        if (state.screen == Screen.Settings) model.openSettings() // the scan changed the embedding count
    }
    val decision = state.lastDecision
    val trashedMessage = decision?.let {
        val trashed = pluralStringResource(R.plurals.trashed_snackbar, it.trashed.size, it.trashed.size)
        if (it.failed.isEmpty()) {
            trashed
        } else {
            "$trashed. " + pluralStringResource(R.plurals.not_moved_snackbar, it.failed.size, it.failed.size)
        }
    }
    val undo = stringResource(R.string.undo)
    LaunchedEffect(decision) {
        if (decision == null || trashedMessage == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(trashedMessage, undo, withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            haptics.performHapticFeedback(Haptic.TICK.type)
            model.undo()
        } else {
            model.dismissUndo()
        }
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
            model.dismissExclusion()
        }
    }
    BackHandler(state.screen != Screen.Home) { model.back() }
    val screen = state.screen
    Scaffold(
        // The category screen has its own host, above the trash button.
        snackbarHost = { if (screen !is Screen.Category) SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Home -> stringResource(R.string.app_name)
                            is Screen.Category -> categoryName(screen.category)
                            Screen.Trash -> stringResource(R.string.trash)
                            Screen.Settings -> stringResource(R.string.settings)
                            Screen.StorageMap -> stringResource(R.string.storage_map)
                            Screen.Thresholds -> stringResource(R.string.settings_thresholds)
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Home) {
                        IconButton(model::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    }
                },
                actions = {
                    if (screen == Screen.Home) {
                        IconButton(model::openSettings) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.settings))
                        }
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
            when (screen) {
                Screen.Home -> {
                    ScanStatus(progress, onScan = { Indexing.scanNow(context) }, indexEmpty = state.indexEmpty)
                    CategoryList(state.categories, state.freeableBytes, model::open, model::openTrash)
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
                )
                Screen.Trash -> TrashScreen(
                    state.trashed,
                    state.selected,
                    model::select,
                    model::selectAll,
                    model::restoreSelected,
                    model::emptyTrash,
                    retentionDays = state.retentionDays,
                )
                Screen.Settings -> SettingsScreen(
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
                )
                Screen.Thresholds -> ThresholdsScreen(
                    state.thresholds,
                    state.thresholdCounts,
                    state.defaultCounts,
                    model::setThreshold,
                    model::resetThresholds,
                )
                Screen.StorageMap -> StorageMapScreen(
                    currentFolder = state.storageMap.lastOrNull() ?: Folder.EMPTY,
                    baseHue = trailHue(state.storageMap),
                    onZoomIn = model::zoomStorageMap,
                    onExclude = { model.excludeFolder(it.path) },
                    atRoot = state.storageMap.size <= 1,
                )
            }
        }
    }
}
