package io.github.bert0ns.cribrum

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManifestTest {
    @Test
    fun `internet permission is absent from merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val permissions = packageInfo.requestedPermissions?.toSet() ?: emptySet()
        assertFalse("INTERNET permission must be removed from manifest", permissions.contains(Manifest.permission.INTERNET))
        assertTrue("MANAGE_EXTERNAL_STORAGE must be present", permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
    }
}
