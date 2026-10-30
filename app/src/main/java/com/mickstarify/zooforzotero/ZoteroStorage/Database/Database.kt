package com.mickstarify.zooforzotero.ZoteroStorage.Database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mickstarify.zooforzotero.ZoteroAPI.Model.ItemPOJO
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.functions.Action
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

@Database(
    entities = arrayOf(
        GroupInfo::class,
        Collection::class,
        RecentlyOpenedAttachment::class,
        ItemInfo::class,
        ItemData::class,
        Creator::class,
        ItemTag::class,
        ItemCollection::class,
        AttachmentInfo::class,
        AttachmentBase::class,
        PendingItemEdit::class,
        FileHash::class
    ),
    version = 14,
    exportSchema = true
)
abstract class ZoteroRoomDatabase : RoomDatabase() {
    abstract fun groupInfoDao(): GroupInfoDao
    abstract fun collectionDao(): CollectionDao
    abstract fun recentlyOpenedAttachmentsDao(): RecentlyOpenedAttachmentDao
    abstract fun itemDao(): ItemDao
    abstract fun AttachmentInfoDao(): AttachmentInfoDao
    abstract fun attachmentBaseDao(): AttachmentBaseDao
    abstract fun pendingItemEditDao(): PendingItemEditDao
    abstract fun fileHashDao(): FileHashDao
}

private const val VACUUM_DUE = "vacuumDue"

@Singleton
class ZoteroDatabase @Inject constructor(val context: Context) {
    // whether a migration left space inside the file's pages for reclaimSpace to give back
    private val maintenance = context.getSharedPreferences("zotero-database", Context.MODE_PRIVATE)

    val db = Room.databaseBuilder(
        context.applicationContext,
        ZoteroRoomDatabase::class.java, "zotero"
    )
        // tag counts, the known user, now run off the main thread; kept until other callers are checked
        .allowMainThreadQueries()
        .addMigrations(MIGRATION_1_2)
        .addMigrations(MIGRATION_2_3)
        .addMigrations(MIGRATION_3_4)
        .addMigrations(MIGRATION_4_5)
        .addMigrations(MIGRATION_5_6)
        .addMigrations(MIGRATION_6_7)
        .addMigrations(MIGRATION_7_8)
        .addMigrations(MIGRATION_8_9)
        .addMigrations(MIGRATION_9_10)
        .addMigrations(MIGRATION_10_11)
        .addMigrations(MIGRATION_11_12)
        .addMigrations(MIGRATION_12_13)
        .addMigrations(object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_13_14.migrate(db)
                maintenance.edit().putBoolean(VACUUM_DUE, true).apply()
            }
        }).build()

    /** md5 of the file this device last downloaded/uploaded for the attachment, or null. */
    fun getAttachmentBase(itemKey: String, group: Int): String? =
        db.attachmentBaseDao().get(itemKey, group)?.md5

    fun setAttachmentBase(itemKey: String, group: Int, md5: String) {
        db.attachmentBaseDao().put(AttachmentBase(itemKey, group, md5, System.currentTimeMillis()))
    }

    fun backfillAttachmentBases() = db.attachmentBaseDao().backfillFromAttachmentInfo()

    fun attachmentKeysWithBase(group: Int): Set<String> =
        db.attachmentBaseDao().keysWithBase(group).toSet()

    /** The last md5 hashed for attachment [itemKey]'s file, with its size and mtime then (FileHash). */
    fun getFileHash(itemKey: String): FileHash? = db.fileHashDao().get(itemKey)

    fun putFileHash(hash: FileHash) = db.fileHashDao().put(hash)

    fun deleteFileHash(itemKey: String) = db.fileHashDao().delete(itemKey)

    fun addGroup(group: GroupInfo): Completable {
        return db.groupInfoDao().insertGroupInfos(group)
    }

    /** Forgets a group the user is no longer in (its row only). Call off the main thread. */
    fun deleteGroup(group: GroupInfo) {
        db.groupInfoDao().delete(group)
    }

    fun getGroups(): Maybe<List<GroupInfo>> {
        return db.groupInfoDao().getAll()
    }

    fun getItemsForGroup(groupID: Int): Maybe<List<Item>> {
        return db.itemDao().getItemsForGroup(groupID)
    }

    /**
     * A library's items (not trashed) with their fields, creators, tags and collections,
     * read with one query per table and joined here by key, rather than through Room's
     * @Relation batches ([getItemsForGroup]). With [listFieldsOnly], regular items get only
     * [LIST_FIELDS], attachments all but their note (Item.hasAllFields false; see
     * [loadRestOfFields]), notes everything. Blocking; call off the main thread.
     */
    fun loadItems(groupID: Int, listFieldsOnly: Boolean = false): List<Item> {
        val dao = db.itemDao()
        val infos = dao.getItemInfosForGroup(groupID)
        val data = HashMap<String, MutableList<ItemData>>(infos.size * 2)
        val creators = HashMap<String, MutableList<Creator>>(infos.size * 2)
        val tags = HashMap<String, MutableList<ItemTag>>()
        val collections = HashMap<String, MutableList<String>>()
        val rows = if (listFieldsOnly) dao.getListItemDataForGroup(groupID, LIST_FIELDS) else dao.getItemDataForGroup(groupID)
        for (d in rows) data.getOrPut(d.parent) { ArrayList(8) }.add(d)
        for (c in dao.getCreatorsForGroup(groupID)) creators.getOrPut(c.parent) { ArrayList(2) }.add(c)
        for (t in dao.getTagsForGroup(groupID)) tags.getOrPut(t.parent) { ArrayList(2) }.add(t)
        for (c in dao.getItemCollectionsForGroup(groupID)) collections.getOrPut(c.itemKey) { ArrayList(1) }.add(c.collectionKey)
        return infos.map { info -> assemble(info, data, creators, tags, collections, listFieldsOnly) }
    }

    private fun assemble(
        info: ItemInfo,
        data: Map<String, List<ItemData>>,
        creators: Map<String, List<Creator>>,
        tags: Map<String, List<ItemTag>>,
        collections: Map<String, List<String>>,
        listFieldsOnly: Boolean,
    ) = Item().apply {
        itemInfo = info
        itemData = data[info.itemKey] ?: emptyList()
        this.creators = creators[info.itemKey] ?: emptyList()
        this.tags = tags[info.itemKey] ?: emptyList()
        this.collections = collections[info.itemKey] ?: emptyList()
        if (listFieldsOnly && itemType != "note") hasAllFields = false
    }

    /**
     * The items with [keys], in that order (keys not found are left out), loaded as
     * [loadItems] does; with [withChildren], each also gets its attachments and notes
     * (Item.adoptChild), as a whole-library load gives them. Blocking.
     */
    fun loadItemsByKeys(keys: List<String>, listFieldsOnly: Boolean = true, withChildren: Boolean = true): List<Item> {
        val items = loadByKeys(keys, listFieldsOnly)
        if (withChildren && items.isNotEmpty()) {
            val byKey = items.associateBy { it.itemKey }
            val childKeys = keys.chunked(KEY_CHUNK).flatMap { db.itemDao().getChildKeys(it) }
            for (child in loadByKeys(childKeys, listFieldsOnly)) {
                child.data["parentItem"]?.let { byKey[it]?.adoptChild(child) }
            }
        }
        return items
    }

    /**
     * The attachments among the items [keys] and their children (not trashed), with all
     * their fields: what bulk downloads and deletions act on. Blocking.
     */
    fun loadAttachmentsOf(keys: List<String>): List<Item> {
        val dao = db.itemDao()
        val own = keys.chunked(KEY_CHUNK).flatMap { dao.getItemInfos(it) }
            .filter { it.itemType == "attachment" && !it.deleted }.map { it.itemKey }
        val children = keys.chunked(KEY_CHUNK).flatMap { dao.getChildKeys(it) }
        return loadByKeys(own + children, listFieldsOnly = false).filter { it.itemType == "attachment" }
    }

    private fun loadByKeys(keys: List<String>, listFieldsOnly: Boolean): List<Item> {
        if (keys.isEmpty()) return emptyList()
        val dao = db.itemDao()
        val infos = HashMap<String, ItemInfo>(keys.size * 2)
        val data = HashMap<String, MutableList<ItemData>>(keys.size * 2)
        val creators = HashMap<String, MutableList<Creator>>(keys.size * 2)
        val tags = HashMap<String, MutableList<ItemTag>>()
        val collections = HashMap<String, MutableList<String>>()
        for (chunk in keys.chunked(KEY_CHUNK)) {
            for (i in dao.getItemInfos(chunk)) infos[i.itemKey] = i
            val rows = if (listFieldsOnly) dao.getListItemData(chunk, LIST_FIELDS) else dao.getItemData(chunk)
            for (d in rows) data.getOrPut(d.parent) { ArrayList(8) }.add(d)
            for (c in dao.getCreators(chunk)) creators.getOrPut(c.parent) { ArrayList(2) }.add(c)
            for (t in dao.getTags(chunk)) tags.getOrPut(t.parent) { ArrayList(2) }.add(t)
            for (c in dao.getItemCollections(chunk)) collections.getOrPut(c.itemKey) { ArrayList(1) }.add(c.collectionKey)
        }
        return keys.mapNotNull { key -> infos[key]?.let { assemble(it, data, creators, tags, collections, listFieldsOnly) } }
    }

    /**
     * The items with [keys], in that order, with only the fields [names] (and a note's
     * text), and their creators if [withCreators]: enough to order a list before its rows
     * load. Blocking.
     */
    fun loadSortItems(keys: List<String>, names: List<String>, withCreators: Boolean): List<Item> {
        if (keys.isEmpty()) return emptyList()
        val dao = db.itemDao()
        val infos = HashMap<String, ItemInfo>(keys.size * 2)
        val data = HashMap<String, MutableList<ItemData>>(keys.size * 2)
        val creators = HashMap<String, MutableList<Creator>>()
        for (chunk in keys.chunked(KEY_CHUNK)) {
            for (i in dao.getItemInfos(chunk)) infos[i.itemKey] = i
            for (d in dao.getSomeItemData(chunk, names)) data.getOrPut(d.parent) { ArrayList(4) }.add(d)
            if (withCreators) for (c in dao.getCreators(chunk)) creators.getOrPut(c.parent) { ArrayList(2) }.add(c)
        }
        return keys.mapNotNull { key ->
            infos[key]?.let { assemble(it, data, creators, emptyMap(), emptyMap(), listFieldsOnly = true) }
        }
    }

    /** Runs a query whose first column is an item key; for the list's generated SQL. Blocking. */
    fun queryKeys(sql: String, args: List<Any>): List<String> =
        db.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { c ->
            ArrayList<String>(c.count).apply { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Whether some of library [groupID]'s items have no SortKeys yet (see fillMissingKeys). Blocking. */
    fun lacksSortKeys(groupID: Int): Boolean = db.itemDao().lacksSortKeys(groupID)

    /**
     * Computes and stores the SortKeys and SearchText of the items with [keys] from their
     * stored fields, creators and tags: after an edit on the device, or for items from
     * before they existed. Blocking.
     */
    fun fillKeys(keys: List<String>) {
        for (chunk in keys.chunked(500)) {
            val items = loadByKeys(chunk, listFieldsOnly = false)
            db.runInTransaction {
                for (item in items) {
                    val k = SortKeys.of(item)
                    val s = SearchText.of(item)
                    db.itemDao().setKeys(
                        item.itemKey, k.title, k.author, k.date, k.dateAdded,
                        s.title, s.creators, s.year, s.publication, s.abstract, s.tags, s.other,
                    )
                }
            }
        }
    }

    /** Gives library [groupID]'s items from before MIGRATION_10_11 or MIGRATION_12_13 their keys. Blocking. */
    fun fillMissingKeys(groupID: Int) {
        val keys = db.itemDao().getKeysWithoutKeys(groupID)
        if (keys.isEmpty()) return
        val start = System.nanoTime()
        fillKeys(keys)
        Log.d("zotero", "sort keys and search text for ${keys.size} items of $groupID in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    /** Whether some of library [groupID]'s items have no SearchText yet (see fillMissingKeys). Blocking. */
    fun lacksSearchText(groupID: Int): Boolean = db.itemDao().lacksSearchText(groupID)

    /**
     * Shrinks the file after a migration that emptied much of its rows (MIGRATION_13_14),
     * or when at least [minFree] bytes of it are unused pages: SQLite reuses free space but
     * never gives it back by itself, and space freed inside pages isn't even counted.
     * Rewrites the whole database: writes wait meanwhile, reads go on. Blocking.
     */
    fun reclaimSpace(minFree: Long = 4L shl 20) {
        val sql = db.openHelper.writableDatabase
        fun pragma(name: String) = sql.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }
        val free = pragma("freelist_count") * pragma("page_size")
        if (!maintenance.getBoolean(VACUUM_DUE, false) && free < minFree) return
        val before = pragma("page_count")
        val start = System.nanoTime()
        sql.execSQL("VACUUM")
        // the rewritten pages are in the write-ahead log, as large as the file, until it's emptied
        sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        maintenance.edit().putBoolean(VACUUM_DUE, false).apply()
        Log.d("zotero", "vacuumed ${before - pragma("page_count")} of $before pages in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    /** How many items (not trashed) library [groupID] has. Blocking. */
    fun countItems(groupID: Int): Int = db.itemDao().countItems(groupID)

    /** Keys of a library's top-level items (not trashed). Blocking. */
    fun topLevelKeys(groupID: Int): List<String> = db.itemDao().getTopLevelKeys(groupID)

    /** For item keys (not trashed): their parent's key, or null for a top-level item. Blocking. */
    fun parentsOf(keys: Iterable<String>): Map<String, String?> =
        keys.toList().chunked(KEY_CHUNK).flatMap { db.itemDao().getParents(it) }
            .associate { it.itemKey to it.parent }

    /**
     * Every tag on a library's top-level items with the number of items carrying it, by
     * name ignoring case (then exactly). Blocking.
     */
    fun tagCounts(groupID: Int): List<Pair<String, Int>> =
        db.itemDao().getTagCounts(groupID).map { it.tag to it.count }
            .sortedWith(compareBy<Pair<String, Int>, String>(String.CASE_INSENSITIVE_ORDER) { it.first }.thenBy { it.first })

    /** The tags on the items [keys], each with how many of them carry it; by name, as [tagCounts]. Blocking. */
    fun tagCountsOf(keys: List<String>): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (chunk in keys.chunked(900)) {
            for (c in db.itemDao().getTagCountsOf(chunk)) counts[c.tag] = (counts[c.tag] ?: 0) + c.count
        }
        return counts.toList()
            .sortedWith(compareBy<Pair<String, Int>, String>(String.CASE_INSENSITIVE_ORDER) { it.first }.thenBy { it.first })
    }

    /** The fields [loadItems] left out with listFieldsOnly, by item key. Blocking. */
    fun loadRestOfFields(groupID: Int): Map<String, List<ItemData>> =
        db.itemDao().getRestOfItemDataForGroup(groupID, LIST_FIELDS).groupBy { it.parent }

    /** One item's fields that [loadItems] left out with listFieldsOnly. Blocking. */
    fun loadRestOfFields(itemKey: String): List<ItemData> =
        db.itemDao().getRestOfItemData(itemKey, LIST_FIELDS)

    companion object {
        /** Keys per IN (...) list: SQLite allows 999 parameters, and some queries add a few. */
        const val KEY_CHUNK = 900

        /**
         * The fields a regular item loads at startup: what lists, sorting, the filter and
         * the default search (title, creators, year) read. Abstracts, publication details,
         * URLs and the rest load afterwards.
         */
        val LIST_FIELDS = listOf(
            "itemType", "title", "shortTitle", "caseName", "nameOfAct", "date", "dateAdded",
            "inPublications", "parentItem",
        )
    }

    fun getItemKeysWithTag(tag: String): Maybe<List<String>> {
        return db.itemDao().getItemKeysWithTag(tag)
    }

    fun writeItemPOJOs(
        groupID: Int,
        itemsPOJO: List<ItemPOJO>
    ): Completable {
        Log.d("zotero", "writeItemPOJOs() - writing ${itemsPOJO.size} items for groupID=$groupID")
        var completable: Completable = Completable.complete()
        for (item in itemsPOJO) {
            completable = completable.andThen(writeItem(groupID, item))
        }
        return completable.doOnComplete {
            Log.d(
                "zotero",
                "writeItemPOJOs() - completed writing ${itemsPOJO.size} items for groupID=$groupID"
            )
        }.doOnError { error ->
            Log.e("zotero", "writeItemPOJOs() - error writing items: $error")
        }
    }

    fun deleteItem(itemKey: String): Completable {
        return Completable.fromAction(Action {
            db.itemDao().deleteUsingItemKey(itemKey)
        })
    }

    fun deleteCollection(collectionKey: String): Completable {
        return Completable.fromAction(Action {
            db.collectionDao().deleteUsingKey(collectionKey)
        })
    }

    fun writeItem(
        groupID: Int,
        itemPOJO: ItemPOJO
    ): Completable {
        Log.d("zotero", "writeItem() - writing item ${itemPOJO.ItemKey} for groupID=$groupID")
        var itemDatas = LinkedList<ItemData>()
        var itemCreators = LinkedList<Creator>()
        // blank fields aren't stored (see MIGRATION_7_8); a missing field reads as blank
        for ((key, value) in itemPOJO.data) {
            if (value != "") itemDatas.add(ItemData(itemPOJO.ItemKey, key, value, "String"))
        }
        for ((index, creatorPOJO) in itemPOJO.creators.withIndex()) {
            itemCreators.add(
                Creator(
                    itemPOJO.ItemKey,
                    creatorPOJO.firstName,
                    creatorPOJO.lastName,
                    creatorPOJO.creatorType,
                    index
                )
            )
        }
        val asStored = Item().apply {
            itemInfo = ItemInfo(itemPOJO.ItemKey, groupID, itemPOJO.version, false)
            itemData = itemDatas
            // as stored: a creator listed twice is one row (the primary key), the later one
            creators = itemCreators.associateBy { Triple(it.firstName, it.lastName, it.creatorType) }.values.toList()
            tags = itemPOJO.tags.distinct().map { ItemTag(itemPOJO.ItemKey, it) }
            collections = emptyList()
        }
        val sortKeys = SortKeys.of(asStored)
        val searchText = SearchText.of(asStored)
        val itemInfo =
            ItemInfo(
                itemPOJO.ItemKey, groupID, itemPOJO.version, Boolean.fromInt(itemPOJO.deleted),
                parent = itemPOJO.data["parentItem"]?.ifEmpty { null },
                itemType = itemPOJO.data["itemType"]?.ifEmpty { null },
                contentType = itemPOJO.data["contentType"]?.ifEmpty { null },
                sortTitle = sortKeys.title,
                sortAuthor = sortKeys.author,
                sortDate = sortKeys.date,
                sortDateAdded = sortKeys.dateAdded,
                searchTitle = searchText.title,
                searchCreators = searchText.creators,
                searchYear = searchText.year,
                searchPublication = searchText.publication,
                searchAbstract = searchText.abstract,
                searchTags = searchText.tags,
                searchOther = searchText.other,
            )
        val itemTags = itemPOJO.tags.map { ItemTag(itemPOJO.ItemKey, it) }
        val collections = itemPOJO.collections.map { ItemCollection(it, itemPOJO.ItemKey) }

        val insertCompletable = Completable.fromAction {
            Log.d("zotero", "About to call insertItem() for ${itemPOJO.ItemKey}")
        }.andThen(
            db.itemDao().insertItem(itemInfo, itemDatas, itemCreators, collections, itemTags)
        ).doOnComplete {
            Log.d("zotero", "writeItem() completed successfully for ${itemPOJO.ItemKey}")
        }.doOnError { error ->
            Log.e("zotero", "writeItem() failed for ${itemPOJO.ItemKey}: $error")
        }
        return insertCompletable
    }

//    fun writeAttachment(item: Item, md5Key: String, downloadedFrom: String): Completable {
//        val attachment = Attachment(item.itemKey, item.groupParent, md5Key, downloadedFrom)
//        return db.AttachmentInfoDao().insertItem(attachment)
//    }

    fun getCollections(groupID: Int): Maybe<List<Collection>> {
        return db.collectionDao().getCollectionsForGroup(groupID)
    }

    fun writeCollections(collections: List<Collection>): Completable {
        return db.collectionDao().insertAllCollections(collections)
    }

    fun addRecentlyOpenedAttachments(recentlyOpenedAttachment: RecentlyOpenedAttachment): Completable {
        return db.recentlyOpenedAttachmentsDao().insert(recentlyOpenedAttachment)
    }

    fun deleteRecentlyOpenedAttachment(itemKey: String): Completable {
        return db.recentlyOpenedAttachmentsDao().delete(itemKey)
    }

    fun getRecentlyOpenedAttachments(): Maybe<List<RecentlyOpenedAttachment>> {
        return db.recentlyOpenedAttachmentsDao().getAll()
    }

    fun getAttachmentsForGroup(groupID: Int): Maybe<List<AttachmentInfo>> {
        return db.AttachmentInfoDao().getAttachmentsForGroup(groupID)
    }

    fun getTrashedItemsForGroup(groupID: Int): Maybe<List<Item>> {
        return db.itemDao().getTrashedItemsForGroup(groupID)
    }

    fun writeAttachmentInfo(
        attachmentInfo: AttachmentInfo
    ): Completable {
        return db.AttachmentInfoDao().updateAttachment(attachmentInfo)
    }

    fun deleteAllItemsForGroup(groupID: Int): Completable {
        return db.itemDao().deleteAllForGroup(groupID)
    }

    fun containsItem(groupID: Int, itemKey: String): Single<Boolean> {
        return db.itemDao().containsItem(groupID, itemKey)
    }

    fun moveItemToTrash(groupID: Int, itemKey: String): Completable {
        return db.itemDao().moveToTrash(groupID, itemKey)
    }

    fun restoreItemFromTrash(groupID: Int, itemKey: String): Completable {
        return db.itemDao().restoreFromTrash(groupID, itemKey)
    }

    fun deleteAllItems(): Completable {
        return db.itemDao().deleteAllItems()
    }

    fun deleteEverything() {
        db.clearAllTables()
    }
}

private fun Boolean.Companion.fromInt(param: Int): Boolean {
    return param != 0
}
