// What a file's folder says about where the image came from. Mirrors lab/src/cribrum_lab/folders.py.
package io.github.bert0ns.cribrum.folders

import java.io.File

/** Folder names used by messaging apps, lowercase, in their old and Android 11+ locations. */
internal val MESSAGING_FOLDERS = setOf(
    "whatsapp", "com.whatsapp", "whatsapp business", "com.whatsapp.w4b", "telegram",
    "org.telegram.messenger", "com.instagram.android", "messenger", "com.facebook.orca", "signal",
    "org.thoughtcrime.securesms",
)

private fun folders(path: String): List<String> = generateSequence(File(path).parentFile) { it.parentFile }
    .map { it.name.lowercase() }.toList()

fun isCameraFolder(path: String): Boolean = "dcim" in folders(path)

fun isMessagingFolder(path: String): Boolean = folders(path).any { it in MESSAGING_FOLDERS }

/**
 * A messaging app's sticker folder, such as WhatsApp's "WhatsApp Stickers" and "WhatsApp Backup
 * Excluded Stickers".
 */
fun isStickerFolder(path: String): Boolean = isMessagingFolder(path) && folders(path).any { it.endsWith("stickers") }

/** Whether [path] is in a folder the user excluded, or in one of its subfolders (docs/design.md §6.3). [excluded] holds folder paths. */
fun isExcluded(path: String, excluded: Collection<String>): Boolean = excluded.any { path.startsWith("$it/") }
