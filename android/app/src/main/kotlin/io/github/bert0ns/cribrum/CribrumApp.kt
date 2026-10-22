package io.github.bert0ns.cribrum

import android.app.Application
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.review.DateGroupingSetting
import io.github.bert0ns.cribrum.review.Thresholds
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.TrashRetention
import io.github.bert0ns.cribrum.ui.HapticsSetting

/** Holds the one index and trash the app and its background work share. */
class CribrumApp : Application() {
    val index: ImageIndex by lazy { ImageIndex.open(this) }
    val trashRetention: TrashRetention by lazy { TrashRetention(getSharedPreferences("settings", MODE_PRIVATE)) }
    val thresholds: Thresholds by lazy { Thresholds(getSharedPreferences("settings", MODE_PRIVATE)) }
    val haptics: HapticsSetting by lazy { HapticsSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val dateGrouping: DateGroupingSetting by lazy { DateGroupingSetting(getSharedPreferences("settings", MODE_PRIVATE)) }
    val trash: AppTrash by lazy { AppTrash.forDevice(this, retention = { trashRetention.duration }) }
}
