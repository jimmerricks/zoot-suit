package com.mickstarify.zooforzotero.ZoteroStorage

import android.util.Log
import com.mickstarify.zooforzotero.ZoteroAPI.RESERVED_SPACE
import java.io.File

/**
 * Moves App storage's attachment folders from [from] (before 0.5.0: the app's cache in
 * shared storage) to [to] (private files), one KEY folder at a time, so it can stop at any
 * point and go on at the next start; other folders there are left alone. Until a folder
 * is moved, AttachmentStorageManager reads it where it is.
 *
 * Within one filesystem a folder is renamed; otherwise (shared storage is a separate
 * mount, even on the same flash) it is copied into "KEY.moving", which becomes KEY only
 * when complete, and the original is deleted after. Each file keeps its modification
 * time: edit detection and the FileHash cache compare it. A folder whose files changed
 * during the copy is left for the next start. A folder already in [to] (downloaded again
 * since) wins, and the old copy is deleted. A copy stops while it would leave less than
 * [reserve] free.
 */
class AppStorageMove(
    private val from: File?,
    private val to: File,
    private val reserve: Long = RESERVED_SPACE,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    /** Renames a KEY folder into [to]; tests fail it to take the copy path. */
    private val renameFolder: (File, File) -> Boolean = { folder, target -> folder.renameTo(target) },
) {
    /** True when nothing is left in [from]. Blocking: not on the main thread. */
    fun run(): Boolean {
        // only attachment folders (KEY, an item key): anything else a library put in the
        // cache stays where it is
        val folders = from?.listFiles()?.filter { it.isDirectory && KEY_FOLDER.matches(it.name) } ?: return true
        if (folders.isEmpty()) return true
        to.mkdirs()
        // half copies of a move that was stopped
        to.listFiles()?.filter { it.name.endsWith(MOVING) }?.forEach { it.deleteRecursively() }

        var all = true
        for (folder in folders) {
            val result = runCatching { moveFolder(folder) }
                .onFailure { Log.w(TAG, "couldn't move ${folder.name}: $it") }
            when (result.getOrNull()) {
                Outcome.MOVED -> {}
                Outcome.CHANGED -> all = false
                Outcome.NO_SPACE -> {
                    Log.w(TAG, "not enough space to move ${folder.name}; the rest stays")
                    return false
                }
                null -> all = false
            }
        }
        if (all) Log.i(TAG, "attachments moved to private storage")
        return all
    }

    private enum class Outcome { MOVED, CHANGED, NO_SPACE }

    private fun moveFolder(folder: File): Outcome {
        val target = File(to, folder.name)
        if (target.exists()) {
            folder.deleteRecursively()
            return Outcome.MOVED
        }
        if (renameFolder(folder, target)) return Outcome.MOVED

        val files = folder.listFiles()?.filter { it.isFile } ?: emptyList()
        if (usableSpace(to) - files.sumOf { it.length() } < reserve) return Outcome.NO_SPACE
        val before = files.associate { it.name to (it.length() to it.lastModified()) }

        val moving = File(to, folder.name + MOVING)
        moving.mkdirs()
        for (file in files) {
            val copy = File(moving, file.name)
            file.copyTo(copy, overwrite = true)
            copy.setLastModified(file.lastModified())
        }

        val after = folder.listFiles()?.filter { it.isFile }
            ?.associate { it.name to (it.length() to it.lastModified()) }
        if (after != before) {
            moving.deleteRecursively()
            return Outcome.CHANGED
        }
        if (!moving.renameTo(target)) {
            // downloaded again meanwhile: that copy is newer
            moving.deleteRecursively()
        }
        folder.deleteRecursively()
        return Outcome.MOVED
    }

    private companion object {
        const val TAG = "AppStorageMove"
        const val MOVING = ".moving"
        val KEY_FOLDER = Regex("[0-9A-Z]{8}")
    }
}
