package com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.ArrayMap
import android.util.Log
import com.mickstarify.zooforzotero.ZoteroAPI.SYNC_ITEM_TYPE_FILTER
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mickstarify.zooforzotero.ZoteroAPI.Model.ItemPOJO
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File
import java.io.InputStreamReader
import java.util.LinkedList

class ZoteroDB(
    val context: Context,
    val groupID: Int
) {
    private val zoteroDatabase: ZoteroDatabase
    init {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(context, SingletonComponentsEntryPoint::class.java)
        zoteroDatabase = hiltEntryPoint.getZoteroDatabase()
    }

    val prefix = if (groupID == Collection.NO_GROUP_ID) {
        ""
    } else {
        groupID.toString()
    }

    // replaced whole (never mutated) by loads on the io scheduler and read from the main
    // thread and from background checks; @Volatile so a reader sees the new list
    @Volatile
    var collections: List<Collection>? = null
        set(value) {
            field = value
            this.populateCollectionChildren()
            this.createCollectionItemMap()
        }
    @Volatile
    var items: List<Item>? = null
        set(value) {
            field = value
            itemsByKey = value?.associateBy { it.itemKey } ?: emptyMap()
            this.associateItemsWithAttachments()
            this.createCollectionItemMap()
            this.processItems()
        }

    // list of items that are in the "my publications"
    var myPublications: MutableList<Item>? = null

    // map that stores attachmentItem classes by ItemKey
//    var attachments: MutableMap<String, MutableList<Item>>? = null
    // map that stores attachmentInfo classes by ItemKey.
    // This is used to store metadata related to items that don't go in the item database class
    // such a design was picked to seperate the data that is from the zotero api official server and
    // the metadata i store customly.
    var attachmentInfo: MutableMap<String, AttachmentInfo>? = null
    private var notes: MutableMap<String, MutableList<Note>>? = null
    private var itemsFromCollections: HashMap<String, MutableList<Item>>? = null

    private var trashItems: List<Item>? = null

    // items by key, rebuilt whenever items is set; lookups by key are frequent (per row,
    // per downloaded attachment) and a scan of a 20k-item library each time adds up
    @Volatile
    private var itemsByKey: Map<String, Item> = emptyMap()

    /**
     * Keys of items with a PDF: parents of a PDF attachment, and standalone PDFs. Built
     * with [items], so the list's "has PDF" filter is a set lookup per item rather than
     * a walk through each item's attachments and their fields on every redraw.
     */
    @Volatile
    var keysWithPdf: Set<String> = emptySet()
        private set

    val namespace: String = if (prefix == "") {
        "zoteroDB"
    } else {
        "zoteroDB_${prefix}"
    }

    fun isPopulated(): Boolean {
        return !(collections == null || items == null)
    }

    fun updateDatabaseLastSyncedTimestamp() {
        val editor = context.getSharedPreferences(namespace, MODE_PRIVATE).edit()
        val timestamp = System.currentTimeMillis() //timestamp in milliseconds.
        editor.putLong("lastSynced", timestamp)
        editor.apply()
        Log.d("zotero", "updated last modified timestamp. $timestamp")
    }

    /* Returns the timestamp in milliseconds of when the database was last updated.*/
    fun getLastSyncedTimestamp(): Long {
        val sp = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val timestamp = sp.getLong("lastSynced", 0L)
        return timestamp
    }


    fun loadCollectionsFromDatabase(): Completable {
        return Completable.fromMaybe(zoteroDatabase.getCollections(groupID).doOnSuccess(
            Consumer {
                collections = it
            }
        ))
    }

    fun loadItemsFromDatabase(): Completable {
        /* Load the items from Database as well as all the attachments. */
        val completable =
            Completable.fromMaybe(zoteroDatabase.getItemsForGroup(groupID).doOnSuccess(
                Consumer {
                    Log.d("zotero", "loaded ${it.size} items from DB for groupID=$groupID, setting now.")
                    items = it
                }
            )).andThen(
                Completable.fromMaybe(
                    zoteroDatabase.getAttachmentsForGroup(groupID).doOnSuccess(Consumer {
                        Log.d("zotero", "Loading attachmentInfo from Database")
                        attachmentInfo = HashMap<String, AttachmentInfo>()
                        for (attachment in it) {
                            attachmentInfo!![attachment.itemKey] = attachment
                        }
                    })
                )
            )
        return completable
    }


    fun loadTrashItemsFromDB(): Completable{
        return Completable.fromMaybe(zoteroDatabase.getTrashedItemsForGroup(groupID).doOnSuccess{
            this.trashItems = it
        })
    }




    fun getAttachments(itemKey: String): List<Item> {
        if (items == null) {
            Log.e("Zotero", "Error database unloaded.")
            this.loadItemsFromDatabase()
                .subscribeOn(Schedulers.io())
                .subscribe()
            return emptyList()
        }

        return itemsByKey[itemKey]?.attachments ?: emptyList()
    }

    private fun associateItemsWithAttachments() {
        val withPdf = HashSet<String>()
        for (item in items!!) {
            if (item.isDownloadable()) {
                val parentKey = item.data["parentItem"]
                if (parentKey != null) {
                    itemsByKey[parentKey]?.attachments?.add(item)
                }
                item.pdfOwnerKey()?.let { withPdf.add(it) }
            }
            if (item.itemType == "note") {
                try {
                    val note = Note(item)
                    if (itemsByKey.containsKey(note.parent) == true) {
                        itemsByKey[note.parent]?.notes?.add(note)
                    }
                } catch (e: ExceptionInInitializerError) {
                    Log.e("zotero", "error loading note ${item.itemKey} error:${e.message}")
                }
            }
        }
        keysWithPdf = withPdf
    }

    fun processItems() {
        /* This method will populate the notes list and the my publications list. */
        if (!isPopulated()) {
            return
        }

        this.notes = ArrayMap()
        this.myPublications = LinkedList()
        items?.forEach { item: Item ->
            if (item.data.containsKey("inPublications") && item.data["inPublications"] == "true"){
                this.myPublications!!.add(item)
            }
        }
    }

    fun createCollectionItemMap() {
        /*all non-nullable assumptions are valid here*/
        if (!isPopulated()) {
            return
        }
        itemsFromCollections = HashMap()
        for (item: Item in this.items!!) {
            for (collection in item.collections) {
                if (!itemsFromCollections!!.containsKey(collection)) {
                    itemsFromCollections!![collection] = LinkedList<Item>()
                }
                itemsFromCollections!![collection]!!.add(item)
            }
        }
    }

    fun getItemsWithoutCollection(): List<Item> {
        if (this.items == null) {
            Log.e("zotero", "Error, items not loaded yet.")
            return LinkedList()
        }
        return (this.getDisplayableItems().filter {
            it.collections.isEmpty()
        })
    }

    /*we will do O(n^2) because I'm not bothered to create a map for what i presume is a small list.*/
    fun populateCollectionChildren() {
        if (collections == null) {
            throw Exception("called populate collections with no collections!")
        }
        for (collection in collections!!) {
            if (collection.hasParent()) {
                collections?.filter { it.key == collection.getParent() }?.firstOrNull()
                    ?.addSubCollection(collection)
            }
        }
    }

    fun getDisplayableItems(): List<Item> {
        if (items != null) {
            return items!!.filter { !it.hasParent() }
        } else {
            Log.e("zotero", "error. got request for getDisplayableItems() before items has loaded.")
            return LinkedList()
        }
    }

    fun getItemsFromCollection(collection: String): List<Item> {
        if (this.itemsFromCollections == null) {
            this.createCollectionItemMap()
        }
        return this.itemsFromCollections?.get(collection) ?: LinkedList<Item>() as List<Item>
    }

    fun setItemsVersion(libraryVersion: Int) {
        Log.d("zotero", "setting library version ${libraryVersion} on ${namespace}")
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putInt("ItemsLibraryVersion", libraryVersion)
        // a reset items version means a full resync; collections start over with it
        if (libraryVersion <= 0) editor.remove("CollectionsLibraryVersion")
        editor.apply()
    }

    /**
     * The library version the collections on this device are complete up to. Separate
     * from the items' version so that a failed collections request is retried at the
     * next sync rather than skipped by it. Until a sync stores one (installs from before
     * it existed), it follows the items' version, as before.
     */
    fun getCollectionsVersion(): Int =
        context.getSharedPreferences(namespace, MODE_PRIVATE)
            .getInt("CollectionsLibraryVersion", getLibraryVersion())

    fun setCollectionsVersion(version: Int) {
        context.getSharedPreferences(namespace, MODE_PRIVATE).edit()
            .putInt("CollectionsLibraryVersion", version).apply()
    }

    fun setDownloadProgress(progress: ItemsDownloadProgress) {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putInt("downloadedAmount", progress.nDownloaded)
        editor.putInt("total", progress.total)
        editor.putInt("downloadVersion", progress.libraryVersion)
        editor.putString("downloadFilter", SYNC_ITEM_TYPE_FILTER)
        editor.apply()
    }

    fun destroyDownloadProgress() {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.remove("downloadedAmount")
        editor.remove("total")
        editor.remove("downloadVersion")
        editor.remove("downloadFilter")
        editor.commit()
    }

    fun getDownloadProgress(): ItemsDownloadProgress? {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val nDownload = sharedPreferences.getInt("downloadedAmount", 0)
        if (nDownload == 0) {
            return null
        }

        val total = sharedPreferences.getInt("total", 0)
        val downloadVersion = sharedPreferences.getInt("downloadVersion", 0)
        if (nDownload == 0 || total == nDownload) {
            // completed job. there is no download progress.
            return null
        }
        if (sharedPreferences.getString("downloadFilter", "") != SYNC_ITEM_TYPE_FILTER) {
            // started under a different item filter; its offset doesn't apply. Start over.
            Log.d("zotero", "discarding download progress made under a different item filter")
            return null
        }
        return ItemsDownloadProgress(downloadVersion, nDownload, total)
    }

    fun getLastDeletedItemsCheckVersion(): Int {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        return sharedPreferences.getInt("LastDeletedItemsCheckVersion", 0)
    }

    fun setLastDeletedItemsCheckVersion(version: Int) {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putInt("LastDeletedItemsCheckVersion", version)
        editor.commit()
    }

    fun getLibraryVersion(): Int {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        return sharedPreferences.getInt("ItemsLibraryVersion", -1)
    }

    fun getTrashVersion(): Int {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        return sharedPreferences.getInt("TrashLibraryVersion", -1)
    }

    fun setTrashVersion(version: Int) {
        val sharedPreferences = context.getSharedPreferences(namespace, MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putInt("TrashLibraryVersion", version)
        editor.apply()
    }

    fun clearItemsVersion() {
        try {
            val sharedPreferences =
                context.getSharedPreferences(namespace, MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.remove("ItemsLibraryVersion")
            editor.remove("CollectionsLibraryVersion")
            editor.apply()
        } catch (e: Exception) {
            Log.e(context.packageName, "error clearing items version from local db.")
        }
    }


    fun deleteItem(key: String) {
        val newItems = LinkedList<Item>()
        items?.forEach {
            if (it.itemKey != key) {
                newItems.add(it)
            }
        }
        items = newItems
    }

    fun getItemWithKey(itemKey: String): Item? {
        return itemsByKey[itemKey]
    }


    fun getMd5Key(item: Item, onlyWebdav: Boolean = false): String {
        if (attachmentInfo == null) {
            Log.e("zotero", "error attachment metadata isn't loaded")
            return ""
        }
        val attachmentInfo = this.attachmentInfo!![item.itemKey]
        if (attachmentInfo == null) {
            val md5Key = item.data["md5"]
            if (md5Key != null) {
                return md5Key
            }
            Log.d("zotero", "No metadata available for ${item.itemKey}")
            return ""
        }

        return attachmentInfo.md5Key
    }


    fun updateAttachmentMetadata(
        itemKey: String,
        md5Key: String,
        mtime: Long,
        downloadedFrom: String = AttachmentInfo.UNSET
    ): Completable {
        val mDownloadedFrom = if (downloadedFrom == AttachmentInfo.UNSET) {
            // check to see if webdav is on, which implies that i was downloaded from webdav.
            if (com.mickstarify.zooforzotero.PreferenceManager(context).isWebDAVEnabled()) {
                AttachmentInfo.WEBDAV
            } else {
                AttachmentInfo.ZOTEROAPI
            }
        } else {
            downloadedFrom
        }
        val attachmentInfo = AttachmentInfo(itemKey, groupID, md5Key, mtime, mDownloadedFrom)
        Log.d("zotero", "adding metadata for ${itemKey}, $md5Key - ${mDownloadedFrom}")

        this.attachmentInfo!![itemKey] = attachmentInfo
        return zoteroDatabase.writeAttachmentInfo(attachmentInfo)
    }



    fun getTrashItems(): List<Item> {
        if (this.trashItems == null){
            Log.e("zotero", "error trash not initialized")
            return LinkedList()
        }
        return this.trashItems!!
    }

    fun getCollectionById(collectionKey: String): Collection? {
        if (this.collections == null){
            return null
        }
        for (collection in this.collections!!){
            if (collection.key == collectionKey){
                return collection
            }
        }
        return null
    }

    fun getItemsForTag(tagName: String): List<Item> {
        if (!this.isPopulated()){
            return LinkedList()
        }

        val itemsWithTag = LinkedList<Item>()
        
        for (item in this.items!!){
            if (item.tags.filter { it.tag == tagName }.any()){
                itemsWithTag.add(item)
            }
        }
        return itemsWithTag
    }
}
