package io.github.bert0ns.cribrum

import android.app.Application
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.TrashRetention

/** Holds the one index and trash the app and its background work share. */
class CribrumApp : Application() {
    val index: ImageIndex by lazy { ImageIndex.open(this) }
    val trashRetention: TrashRetention by lazy { TrashRetention(getSharedPreferences("settings", MODE_PRIVATE)) }
    val trash: AppTrash by lazy { AppTrash.forDevice(this, retention = { trashRetention.duration }) }
}
