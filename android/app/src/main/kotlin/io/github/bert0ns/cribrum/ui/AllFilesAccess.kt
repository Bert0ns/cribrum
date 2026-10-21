package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R

/** The system screen where the user grants this app All files access (docs/design.md §4, D7). */
fun allFilesAccessSettings(context: Context) = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))

@Composable
fun AllFilesAccess(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.access_body), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onGrant) { Text(stringResource(R.string.access_grant)) }
    }
}

@Preview
@Composable
private fun AllFilesAccessPreview() {
    MaterialTheme { AllFilesAccess(onGrant = {}) }
}
