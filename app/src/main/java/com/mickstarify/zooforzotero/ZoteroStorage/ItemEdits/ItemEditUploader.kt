package com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits

import android.util.Log
import com.mickstarify.zooforzotero.ZoteroAPI.ItemGoneException
import com.mickstarify.zooforzotero.ZoteroAPI.MAX_ITEMS_PER_WRITE
import com.mickstarify.zooforzotero.ZoteroAPI.NetworkErrors
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo

/**
 * Uploads the metadata edits of My Library's items, [MAX_ITEMS_PER_WRITE] items per round:
 * fetches them as Zotero has them in one request, replays each one's edits onto its copy
 * (ItemEdits.buildPatch) and writes the results in one more, each against the version it
 * was read at. An item that changed in Zotero in between is fetched and written again,
 * once. A note made on this device is created in Zotero under its own key instead.
 * Edits that went up, or whose item is gone, are forgotten; the rest stay for the next
 * sync. Offline, it stops at the first request that fails. Blocking; call off the main
 * thread.
 */
class ItemEditUploader(private val api: ZoteroAPI, private val store: ItemEditStore) {

    /** What an upload did, for the message after it. */
    data class Result(
        val uploaded: Int = 0,
        /** Items removed from Zotero since they were edited here; their edits are dropped. */
        val gone: Int = 0,
        /** New notes whose item was removed from Zotero before they went up; dropped. */
        val orphanedNotes: Int = 0,
        /** Items with a field changed both here and in Zotero; those edits stay. */
        val conflicts: Int = 0,
        /** Additions to collections deleted in Zotero since; dropped. */
        val missingCollections: Int = 0,
        val failures: List<Throwable> = emptyList(),
        val offline: Boolean = false,
    ) {
        val hasProblems get() = gone > 0 || orphanedNotes > 0 || conflicts > 0 ||
                missingCollections > 0 || failures.isNotEmpty()

        operator fun plus(other: Result) = Result(
            uploaded + other.uploaded, gone + other.gone, orphanedNotes + other.orphanedNotes,
            conflicts + other.conflicts, missingCollections + other.missingCollections,
            failures + other.failures, offline || other.offline,
        )
    }

    fun uploadAll(): Result {
        var result = Result()
        val pending = store.pending(GroupInfo.NO_GROUP_ID)
        val (notes, items) = pending.keys.partition { key -> pending.getValue(key).any { it.kind == ItemEditKind.CREATE_NOTE } }
        for (key in notes) {
            result += uploadNote(key)
            if (result.offline) return result
        }
        for (batch in items.chunked(MAX_ITEMS_PER_WRITE)) {
            result += uploadBatch(batch)
            if (result.offline) return result
        }
        return result
    }

    private fun uploadNote(key: String): Result {
        val edits = store.beginUpload(GroupInfo.NO_GROUP_ID, key)
        try {
            if (edits.isEmpty()) return Result() // undone since the list was read
            val creation = edits.first { it.kind == ItemEditKind.CREATE_NOTE }
            return if (createNote(key, creation)) {
                store.remove(edits)
                Result(uploaded = 1)
            } else {
                store.dropNewNote(key, edits)
                Result(orphanedNotes = 1)
            }
        } catch (e: Exception) {
            Log.e("zotero", "creating the note $key failed: $e")
            return if (NetworkErrors.isOffline(e)) Result(offline = true) else Result(failures = listOf(e))
        } finally {
            store.endUpload(key)
        }
    }

    /** Uploads the edits of the items [keys] (at most [MAX_ITEMS_PER_WRITE]). */
    private fun uploadBatch(keys: List<String>): Result {
        val edits = keys.associateWith { store.beginUpload(GroupInfo.NO_GROUP_ID, it) }
            .filterValues { it.isNotEmpty() } // undone since the list was read
            .mapValues { it.value.toMutableList() }
        var result = Result()
        try {
            var toSend = edits.keys.toList()
            for (attempt in 1..2) {
                val retry = ArrayList<String>()
                val server = api.getItemsData(toSend)
                val written = ArrayList<Pair<String, ItemEdits.Patch>>()
                for (key in toSend) {
                    val itemEdits = edits.getValue(key)
                    val (version, data) = server[key] ?: run {
                        store.remove(itemEdits)
                        result += Result(gone = 1)
                        null
                    } ?: continue
                    val patch = ItemEdits.buildPatch(data, itemEdits)
                    if (patch.body.size() == 0) {
                        result += done(itemEdits, patch) // Zotero has them all already
                    } else {
                        written += key to patch
                    }
                }
                if (written.isEmpty()) break
                val outcomes = api.updateItems(written.map { (key, patch) ->
                    patch.body.deepCopy().apply {
                        addProperty("key", key)
                        addProperty("version", server.getValue(key).first)
                    }
                })
                for ((index, entry) in written.withIndex()) {
                    val (key, patch) = entry
                    val itemEdits = edits.getValue(key)
                    val failure = outcomes[index]
                    when {
                        failure == null -> result += done(itemEdits, patch)
                        // changed in Zotero between the read and the write: replay onto the new copy
                        failure.code == 412 && attempt == 1 -> retry += key
                        failure.missingCollection != null && attempt == 1 -> {
                            val dropped = itemEdits.filter {
                                it.kind == ItemEditKind.ADD_TO_COLLECTION && it.name == failure.missingCollection
                            }
                            if (dropped.isEmpty()) {
                                result += Result(failures = listOf(Exception("$key: $failure")))
                            } else {
                                store.remove(dropped)
                                itemEdits -= dropped.toSet()
                                result += Result(missingCollections = 1)
                                if (itemEdits.isNotEmpty()) retry += key // the rest of its edits
                            }
                        }
                        else -> result += Result(failures = listOf(Exception("$key: $failure")))
                    }
                }
                if (retry.isEmpty()) break
                toSend = retry
            }
        } catch (e: Exception) {
            Log.e("zotero", "uploading the edits of ${edits.size} items failed: $e")
            result += if (NetworkErrors.isOffline(e)) Result(offline = true) else Result(failures = listOf(e))
        } finally {
            keys.forEach { store.endUpload(it) }
        }
        return result
    }

    /** [edits] went up but for [patch]'s conflicts, which stay. */
    private fun done(edits: List<ItemEdit>, patch: ItemEdits.Patch): Result {
        store.remove(edits - patch.conflicts.toSet())
        return if (patch.conflicts.isNotEmpty()) Result(conflicts = 1) else Result(uploaded = 1)
    }

    /**
     * Creates the note [noteKey] in Zotero; true if it is there now (created, or by an
     * earlier attempt whose answer was lost: 412, the key exists), false if its parent
     * was removed from Zotero. Throws if it couldn't be created otherwise.
     */
    private fun createNote(noteKey: String, creation: ItemEdit): Boolean {
        val failure = api.createItem(ItemEdits.newNoteJson(noteKey, creation)) ?: return true
        if (failure.code == 412) return true
        if (failure.code == 409) {
            // 409 says the parent wasn't found; make sure before dropping the text
            try {
                api.getItemData(creation.name).blockingGet()
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.any { it is ItemGoneException }) return false
                throw e
            }
        }
        throw Exception("Zotero did not create the note ($failure)")
    }
}
