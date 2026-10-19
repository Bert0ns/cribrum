package io.github.bert0ns.cribrum

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.ui.AllFilesAccess
import io.github.bert0ns.cribrum.ui.ScanStatus
import io.github.bert0ns.cribrum.ui.allFilesAccessSettings

class MainActivity : ComponentActivity() {
    /** Checked again on every resume: the user grants it in the system settings, outside the app. */
    private var hasAllFilesAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    if (hasAllFilesAccess) {
                        Home(Modifier.padding(padding))
                    } else {
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
fun Home(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress by remember { Indexing.progress(context) }.collectAsStateWithLifecycle(null)
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // A scan on every start, and a daily one: later passes only look at new or changed files.
    LaunchedEffect(Unit) {
        Indexing.schedulePeriodic(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS) // the scan's progress notification
        }
        Indexing.scanNow(context)
    }
    Column(modifier.fillMaxSize()) {
        ScanStatus(progress, onScan = { Indexing.scanNow(context) })
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.placeholder))
        }
    }
}
