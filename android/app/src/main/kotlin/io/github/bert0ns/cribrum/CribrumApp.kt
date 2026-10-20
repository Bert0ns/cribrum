package io.github.bert0ns.cribrum

import android.app.Application
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.trash.AppTrash

/** Holds the one index and trash the app and its background work share. */
class CribrumApp : Application() {
    val index: ImageIndex by lazy { ImageIndex.open(this) }
    val trash: AppTrash by lazy { AppTrash.forDevice(this) }
}
