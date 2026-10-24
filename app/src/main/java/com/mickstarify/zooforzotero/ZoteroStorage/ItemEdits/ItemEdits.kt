package com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * What a metadata edit made on this device does. Stored by name (PendingItemEdit.kind), so
 * kinds can be added but not renamed. A kind this version doesn't know (written by a newer
 * one) is left alone: kept, not applied, not uploaded.
 */
enum class ItemEditKind {
    ADD_TAG,
    REMOVE_TAG,

    /** Sets a field of the item's data ("title", "date", ...); "" clears it. */
    SET_FIELD,
}

/**
 * One metadata edit made on this device that Zotero doesn't have yet. Edits are kept as
 * operations, not as a copy of the item: at upload they are replayed, in order, onto the
 * item as Zotero has it then (see [ItemEdits.buildPatch]), so changes made elsewhere in the
 * meantime survive, and what this device doesn't store (a tag's type, automatic or manual)
 * is kept as Zotero has it.
 *
 * @param name the tag, or the field's name
 * @param value a field's new value
 * @param oldValue a field's value before this device first changed it; a different value
 *   in Zotero at upload means it was changed elsewhere too (a conflict)
 * @param id the PendingItemEdit row; 0 for an edit not stored yet
 */
data class ItemEdit(
    val kind: ItemEditKind,
    val name: String,
    val value: String? = null,
    val oldValue: String? = null,
    val id: Long = 0,
)

object ItemEdits {
    /** Zotero's limit for a tag. */
    const val MAX_TAG_LENGTH = 255

    /** A tag as entered, trimmed; null if it can't be a tag (blank, too long). */
    fun cleanTag(input: String): String? {
        val tag = input.trim()
        return if (tag.isEmpty() || tag.length > MAX_TAG_LENGTH) null else tag
    }

    /**
     * How storing [edit] changes an item's [pending] edits: rows to delete, an edit to
     * insert, or neither. [tags] and [fields] are the item as this device shows it (pending
     * edits applied). Opposite edits cancel out (add a tag, then remove it: nothing to
     * upload), and editing a field again replaces the earlier edit but keeps its oldValue.
     * With [fold] false (the item's edits are being uploaded) nothing is deleted: the edit
     * is appended, and replaying in order still gives the right result.
     */
    fun record(
        pending: List<ItemEdit>,
        edit: ItemEdit,
        tags: List<String>,
        fields: Map<String, String>,
        fold: Boolean = true,
    ): Recording = when (edit.kind) {
        ItemEditKind.ADD_TAG, ItemEditKind.REMOVE_TAG -> {
            val adding = edit.kind == ItemEditKind.ADD_TAG
            val opposite = if (adding) ItemEditKind.REMOVE_TAG else ItemEditKind.ADD_TAG
            val undone = pending.lastOrNull { it.kind == opposite && it.name == edit.name }
            when {
                (edit.name in tags) == adding -> Recording.NONE // already so
                undone != null && fold -> Recording(delete = listOf(undone.id))
                else -> Recording(insert = edit)
            }
        }
        ItemEditKind.SET_FIELD -> {
            val current = fields[edit.name] ?: ""
            val value = edit.value ?: ""
            val earlier = pending.filter { it.kind == ItemEditKind.SET_FIELD && it.name == edit.name }
            val base = earlier.firstOrNull()?.oldValue ?: current
            val replaced = if (fold) earlier.map { it.id } else emptyList()
            when {
                value == current -> Recording.NONE
                value == base && fold -> Recording(delete = replaced) // back to Zotero's
                else -> Recording(delete = replaced, insert = edit.copy(value = value, oldValue = base))
            }
        }
    }

    data class Recording(val delete: List<Long> = emptyList(), val insert: ItemEdit? = null) {
        companion object {
            val NONE = Recording()
        }
    }

    /** [tags] with [edits] applied in order. Applying them twice changes nothing more. */
    fun applyToTags(tags: List<String>, edits: List<ItemEdit>): List<String> {
        val result = tags.toMutableList()
        for (edit in edits) when (edit.kind) {
            ItemEditKind.ADD_TAG -> if (edit.name !in result) result.add(edit.name)
            ItemEditKind.REMOVE_TAG -> result.removeAll { it == edit.name }
            else -> {}
        }
        return result
    }

    /** [fields] with [edits] applied in order; a field set to "" is left out (blank isn't stored). */
    fun applyToFields(fields: Map<String, String>, edits: List<ItemEdit>): Map<String, String> {
        val result = fields.toMutableMap()
        for (edit in edits) if (edit.kind == ItemEditKind.SET_FIELD) {
            val value = edit.value ?: ""
            if (value.isEmpty()) result.remove(edit.name) else result[edit.name] = value
        }
        return result
    }

    /**
     * The PATCH that brings the item as Zotero has it ([serverData], the "data" object of
     * GET /items/{key}) up to date with [edits]: only the changed top-level properties.
     * "tags" is always sent whole (Zotero replaces the array); tags Zotero has keep their
     * type. A field changed in Zotero since this device's edit (neither its oldValue nor
     * its new value) is a conflict and left out of the patch.
     */
    fun buildPatch(serverData: JsonObject, edits: List<ItemEdit>): Patch {
        val body = JsonObject()
        val conflicts = ArrayList<ItemEdit>()

        if (edits.any { it.kind == ItemEditKind.ADD_TAG || it.kind == ItemEditKind.REMOVE_TAG }) {
            val serverTags = serverData.getAsJsonArray("tags")?.mapNotNull { it as? JsonObject } ?: emptyList()
            val tags = serverTags.map { it.deepCopy() }.toMutableList()
            for (edit in edits) when (edit.kind) {
                ItemEditKind.ADD_TAG ->
                    if (tags.none { tagName(it) == edit.name }) tags.add(JsonObject().apply { addProperty("tag", edit.name) })
                ItemEditKind.REMOVE_TAG -> tags.removeAll { tagName(it) == edit.name }
                else -> {}
            }
            if (tags != serverTags) body.add("tags", JsonArray().apply { tags.forEach { add(it) } })
        }

        for (edit in edits.filter { it.kind == ItemEditKind.SET_FIELD }.groupBy { it.name }.map { it.value.last() }) {
            val server = serverData.get(edit.name)?.takeUnless { it.isJsonNull }?.asString ?: ""
            val value = edit.value ?: ""
            when (server) {
                value -> {} // Zotero has it already
                edit.oldValue ?: "" -> body.addProperty(edit.name, value)
                else -> conflicts.add(edit)
            }
        }
        return Patch(body, conflicts)
    }

    private fun tagName(tag: JsonObject): String? = tag.get("tag")?.takeUnless { it.isJsonNull }?.asString

    /** [body] empty: nothing to send. [conflicts] stay pending. */
    data class Patch(val body: JsonObject, val conflicts: List<ItemEdit>)
}
