package io.github.bert0ns.cribrum

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.pipeline.LastScan
import io.github.bert0ns.cribrum.review.ReviewOrderSetting
import io.github.bert0ns.cribrum.review.Thresholds
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.TrashRetention
import io.github.bert0ns.cribrum.ui.HapticsSetting

/** Remembers whether the app has already asked for the scan progress notification permission. */
class NotificationPreferences(private val preferences: SharedPreferences) {
    var permissionRequested: Boolean
        get() = preferences.getBoolean(KEY, false)
        set(value) = preferences.edit { putBoolean(KEY, value) }

    private companion object {
        const val KEY = "notification_permission_requested"
    }
}

/** Holds the one index and trash the app and its background work share. */
class CribrumApp : Application() {
    val index: ImageIndex by lazy { ImageIndex.open(this) }
    val trashRetention: TrashRetention by lazy { TrashRetention(getSharedPreferences("settings", MODE_PRIVATE)) }
    val thresholds: Thresholds by lazy { Thresholds(getSharedPreferences("settings", MODE_PRIVATE)) }
    val haptics: HapticsSetting by lazy { HapticsSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val reviewOrder: ReviewOrderSetting by lazy { ReviewOrderSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val lastScan: LastScan by lazy { LastScan(getSharedPreferences("settings", MODE_PRIVATE)) }
    val notificationPreferences: NotificationPreferences by lazy {
        NotificationPreferences(getSharedPreferences("settings", MODE_PRIVATE))
    }
    val trash: AppTrash by lazy { AppTrash.forDevice(this, retention = { trashRetention.duration }) }
}
