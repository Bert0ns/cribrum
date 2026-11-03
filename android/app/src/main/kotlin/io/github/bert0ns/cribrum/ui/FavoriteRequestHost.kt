// Launches the system request that marks photos as favourites again (docs/design.md §9).
package io.github.bert0ns.cribrum.ui

import android.app.Activity
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bert0ns.cribrum.sources.FavoriteRequests

/**
 * Takes the requests the batch and the trash queue and launches them one at a time, whichever screen is open,
 * while the activity is started. A request that comes while the app is closed waits for the next start.
 */
@Composable
fun FavoriteRequestHost() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        FavoriteRequests.answered(it.resultCode == Activity.RESULT_OK)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val ask = FavoriteRequests.next()
                if (!ask.stillWanted()) continue
                runCatching { ask.intentSender(context.contentResolver) }
                    .onSuccess {
                        FavoriteRequests.launched(ask)
                        launcher.launch(IntentSenderRequest.Builder(it).build())
                    }
                    .onFailure {
                        Log.w("favorites", "Could not build the request to mark the favourites", it)
                        ask.onAnswer(ask.uris.size)
                    }
            }
        }
    }
    // A rotation brings the answer to the new activity, but one that is finished never gets it.
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) FavoriteRequests.abandon() }
    }
}
