package io.github.bert0ns.cribrum

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.bert0ns.cribrum.ui.AllFilesAccess
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.placeholder))
    }
}

@Preview
@Composable
private fun HomePreview() {
    MaterialTheme { Home() }
}
