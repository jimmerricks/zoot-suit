package com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits

import android.util.Log
import com.mickstarify.zooforzotero.ZoteroAPI.ItemChangedSinceException
import com.mickstarify.zooforzotero.ZoteroAPI.ItemGoneException
import com.mickstarify.zooforzotero.ZoteroAPI.NetworkErrors
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo

/**
 * Uploads the metadata edits of My Library's items, one item at a time: fetches the item
 * as Zotero has it, replays the edits onto it (ItemEdits.buildPatch) and PATCHes the result
 * against that version. If the item changes in Zotero in between, it is fetched again.
 * Edits that went up, or whose item is gone, are forgotten; the rest stay for the next
 * sync. Offline, it stops at the first item. Blocking; call off the main thread.
 */
class ItemEditUploader(private val api: ZoteroAPI, private val store: ItemEditStore) {

    /** What an upload did, for the message after it. */
    data class Result(
        val uploaded: Int = 0,
        /** Items removed from Zotero since they were edited here; their edits are dropped. */
        val gone: Int = 0,
        /** Items with a field changed both here and in Zotero; those edits stay. */
        val conflicts: Int = 0,
        val failures: List<Throwable> = emptyList(),
        val offline: Boolean = false,
    ) {
        val hasProblems get() = gone > 0 || conflicts > 0 || failures.isNotEmpty()
    }

    fun uploadAll(): Result {
        var result = Result()
        for ((key, edits) in store.pending(GroupInfo.NO_GROUP_ID)) {
            store.beginUpload(key)
            try {
                val conflicts = upload(key, edits)
                result = if (conflicts) result.copy(conflicts = result.conflicts + 1)
                else result.copy(uploaded = result.uploaded + 1)
            } catch (e: ItemGoneException) {
                store.remove(edits)
                result = result.copy(gone = result.gone + 1)
            } catch (e: Exception) {
                Log.e("zotero", "uploading the edits of $key failed: $e")
                if (NetworkErrors.isOffline(e)) return result.copy(offline = true)
                result = result.copy(failures = result.failures + e)
            } finally {
                store.endUpload(key)
            }
        }
        return result
    }

    /** Uploads [edits] of one item; true if some conflicted (and stay pending). */
    private fun upload(itemKey: String, edits: List<ItemEdit>, attempt: Int = 1): Boolean {
        val (version, data) = api.getItemData(itemKey).blockingGet()
        val patch = ItemEdits.buildPatch(data, edits)
        if (patch.body.size() > 0) {
            try {
                api.patchItemData(itemKey, patch.body, version).blockingAwait()
            } catch (e: Exception) {
                // changed between the GET and the PATCH: replay onto the new copy, once
                if (generateSequence<Throwable>(e) { it.cause }.any { it is ItemChangedSinceException } && attempt == 1) {
                    return upload(itemKey, edits, attempt = 2)
                }
                throw e
            }
        }
        store.remove(edits - patch.conflicts.toSet())
        return patch.conflicts.isNotEmpty()
    }
}
