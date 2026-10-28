package com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * A library held in memory, as the list reads it (ZoteroDB). An interface so that
 * InMemoryItemSource can be tested without Android.
 */
interface LoadedLibrary {
    /** Every item, children too; null until loaded. */
    val items: List<Item>?
    val collections: List<Collection>?
    /** Keys of items with a PDF: parents of a PDF attachment, and standalone PDFs. */
    val keysWithPdf: Set<String>
    /** False while the fields left out at load are still loading. */
    val allFieldsLoaded: Boolean

    /** Top-level items. */
    fun getDisplayableItems(): List<Item>
    fun getItemsFromCollection(collection: String): List<Item>
    fun getItemsWithoutCollection(): List<Item>
    fun getTrashItems(): List<Item>
    fun getMyPublications(): List<Item>
    fun getItemWithKey(itemKey: String): Item?
}
