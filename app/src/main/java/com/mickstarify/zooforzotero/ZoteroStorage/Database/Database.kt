package com.mickstarify.zooforzotero.ZoteroStorage.Database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        PendingItemEdit::class
    ),
    version = 9,
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
}

@Singleton
class ZoteroDatabase @Inject constructor(val context: Context) {
    val db = Room.databaseBuilder(
        context.applicationContext,
        ZoteroRoomDatabase::class.java, "zotero"
    )
        .allowMainThreadQueries() // I promise I will use this wisely. So far it's only used for querying tags.
        .addMigrations(MIGRATION_1_2)
        .addMigrations(MIGRATION_2_3)
        .addMigrations(MIGRATION_3_4)
        .addMigrations(MIGRATION_4_5)
        .addMigrations(MIGRATION_5_6)
        .addMigrations(MIGRATION_6_7)
        .addMigrations(MIGRATION_7_8)
        .addMigrations(MIGRATION_8_9).build()

    /** md5 of the file this device last downloaded/uploaded for the attachment, or null. */
    fun getAttachmentBase(itemKey: String, group: Int): String? =
        db.attachmentBaseDao().get(itemKey, group)?.md5

    fun setAttachmentBase(itemKey: String, group: Int, md5: String) {
        db.attachmentBaseDao().put(AttachmentBase(itemKey, group, md5, System.currentTimeMillis()))
    }

    fun backfillAttachmentBases() = db.attachmentBaseDao().backfillFromAttachmentInfo()

    fun attachmentKeysWithBase(group: Int): Set<String> =
        db.attachmentBaseDao().keysWithBase(group).toSet()

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
        return infos.map { info ->
            Item().apply {
                itemInfo = info
                itemData = data[info.itemKey] ?: emptyList()
                this.creators = creators[info.itemKey] ?: emptyList()
                this.tags = tags[info.itemKey] ?: emptyList()
                this.collections = collections[info.itemKey] ?: emptyList()
                if (listFieldsOnly && itemType != "note") hasAllFields = false
            }
        }
    }

    /** The fields [loadItems] left out with listFieldsOnly, by item key. Blocking. */
    fun loadRestOfFields(groupID: Int): Map<String, List<ItemData>> =
        db.itemDao().getRestOfItemDataForGroup(groupID, LIST_FIELDS).groupBy { it.parent }

    /** One item's fields that [loadItems] left out with listFieldsOnly. Blocking. */
    fun loadRestOfFields(itemKey: String): List<ItemData> =
        db.itemDao().getRestOfItemData(itemKey, LIST_FIELDS)

    companion object {
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
        val itemInfo =
            ItemInfo(itemPOJO.ItemKey, groupID, itemPOJO.version, Boolean.fromInt(itemPOJO.deleted))
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
