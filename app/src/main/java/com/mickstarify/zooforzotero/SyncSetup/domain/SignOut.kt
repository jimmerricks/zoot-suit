package com.mickstarify.zooforzotero.SyncSetup.domain

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.mickstarify.zooforzotero.MainActivity
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import java.io.File

/**
 * Signing out leaves the device as a fresh install would, except for attachment files in a
 * folder the user chose (Custom Path), which are theirs. Gone: the login, every library's
 * data and sync versions, the settings (WebDAV login included) and the app's own attachment
 * cache. The process then restarts into sign-in, so nothing of the old account survives in
 * memory (the API client, loaded libraries, singletons).
 */
object SignOut {
    /** What signing out would throw away: edits made on this device that Zotero doesn't have yet. */
    data class UnsyncedEdits(val newNotes: Int, val editedItems: Int, val editedAttachments: Int) {
        val isEmpty get() = newNotes == 0 && editedItems == 0 && editedAttachments == 0

        /** The lines naming them, for the sign-out dialog. */
        fun describe(): List<String> = listOfNotNull(
            newNotes.takeIf { it > 0 }?.let { if (it == 1) "1 new note" else "$it new notes" },
            editedItems.takeIf { it > 0 }?.let { if (it == 1) "tag changes on 1 item" else "tag changes on $it items" },
            editedAttachments.takeIf { it > 0 }?.let {
                if (it == 1) "1 edited attachment not uploaded" else "$it edited attachments not uploaded"
            },
        )
    }

    /**
     * Counts My Library's edits waiting to sync (only My Library has any). Hashes
     * downloaded files whose size or mtime changed, so call it off the main thread.
     */
    fun unsyncedEdits(database: ZoteroDatabase, storage: AttachmentStorageManager): UnsyncedEdits {
        val group = GroupInfo.NO_GROUP_ID
        val pending = ItemEditStore(database).pending(group)
        val newNotes = pending.count { (_, edits) -> edits.any { it.kind == ItemEditKind.CREATE_NOTE } }
        return UnsyncedEdits(
            newNotes = newNotes,
            editedItems = pending.size - newNotes,
            // whether uploading is on or not: signing out loses the edit either way
            editedAttachments = EditedAttachments(database, storage) { group }.edited().size,
        )
    }

    fun signOut(activity: Activity, database: ZoteroDatabase) {
        database.deleteEverything()

        // login (credentials), library versions (one file per library), settings
        val prefsDir = File(activity.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.map { it.name.removeSuffix(".xml") }?.forEach { name ->
            // commit, not apply: the process exits before a background write would run
            activity.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            activity.deleteSharedPreferences(name)
        }

        // downloaded attachments in the default location, plus temporary files
        activity.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        activity.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        val restart = Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java))
        activity.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }
}
