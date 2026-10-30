package com.mickstarify.zooforzotero.ZoteroStorage.Database

import android.os.Parcelable
import android.text.Html
import android.util.Log
import androidx.room.*
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import kotlinx.parcelize.Parcelize
import java.util.LinkedList

/*this code is currently inactive. Just part of my project to migrate to sQL rather than using
* json files for storage. */

@Entity(
    tableName = "ItemInfo", primaryKeys = ["itemKey", "group"],
    indices = arrayOf(Index(value = ["itemKey"], unique = true), Index(value = ["parent"]))
)
@Parcelize
class ItemInfo(
    @ColumnInfo(name = "itemKey") val itemKey: String,
    @ColumnInfo(name = "group") val groupParent: Int = Collection.NO_GROUP_ID,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "deleted") val deleted: Boolean,
    // Copies of the item's parentItem, itemType and contentType fields (ItemData), so that
    // the list's queries find children, attachments and PDFs through ItemInfo alone. Set
    // where items are written (ZoteroDatabase.writeItem); MIGRATION_9_10 filled them in.
    @ColumnInfo(name = "parent") val parent: String? = null,
    @ColumnInfo(name = "itemType") val itemType: String? = null,
    @ColumnInfo(name = "contentType") val contentType: String? = null,
    // The item's SortKeys, so that a list is ordered in SQL; null until computed (written
    // with the item, refreshed by an edit, filled in for items from before MIGRATION_10_11).
    @ColumnInfo(name = "sortTitle") val sortTitle: String? = null,
    @ColumnInfo(name = "sortAuthor") val sortAuthor: String? = null,
    @ColumnInfo(name = "sortDate") val sortDate: String? = null,
    @ColumnInfo(name = "sortDateAdded") val sortDateAdded: String? = null,
    // The item's SearchText, so that a search runs in SQL; null until computed, like the
    // sort keys (items from before MIGRATION_12_13 are filled in when the library opens).
    // searchAbstract is also null when the abstract needs no folded copy (SearchText).
    @ColumnInfo(name = "searchTitle") val searchTitle: String? = null,
    @ColumnInfo(name = "searchCreators") val searchCreators: String? = null,
    @ColumnInfo(name = "searchYear") val searchYear: String? = null,
    @ColumnInfo(name = "searchPublication") val searchPublication: String? = null,
    @ColumnInfo(name = "searchAbstract") val searchAbstract: String? = null,
    @ColumnInfo(name = "searchTags") val searchTags: String? = null,
    @ColumnInfo(name = "searchOther") val searchOther: String? = null,
) : Parcelable {

    //@Relation(entity = ItemData::class, parentColumn = "itemKey", entityColumn = "parent")
    //lateinit var itemData: List<ItemData>
}
@Parcelize
class Item : Parcelable {
    companion object {
        val ATTACHMENT_TYPE = "attachment"
    }

    @Embedded
    lateinit var itemInfo: ItemInfo

    @Relation(entity = ItemData::class, parentColumn = "itemKey", entityColumn = "parent")
    lateinit var itemData: List<ItemData>

    @Relation(entity = Creator::class, parentColumn = "itemKey", entityColumn = "parent")
    lateinit var creators: List<Creator>

    @Relation(entity = ItemTag::class, parentColumn = "itemKey", entityColumn = "parent")
    lateinit var tags: List<ItemTag>

    @Relation(
        entity = ItemCollection::class,
        parentColumn = "itemKey",
        entityColumn = "itemKey",
        projection = arrayOf("collectionKey")
    )
    lateinit var collections: List<String>

    @Ignore
    var attachments = LinkedList<Item>()

    @Ignore
    var notes = LinkedList<Note>()

    fun getGroup(): Int {
        return itemInfo.groupParent
    }

    fun getSortedCreators(): List<Creator> {
        return this.creators.sortedBy { it.order }
    }

    fun getVersion(): Int {
        return itemInfo.version
    }

    fun getItemData(key: String): String? = data[key]

    val itemKey: String
        get() = itemInfo.itemKey

    val itemType: String
        get() = data["itemType"] ?: "error"

    @Ignore
    var mappedData: MutableMap<String, String>? = null

    @delegate:Ignore
    private val loadedData: Map<String, String> by lazy {
        if (mappedData == null) {
            mappedData = HashMap()
            for (iData in itemData) {
                mappedData!![iData.name] = iData.value
            }
        }
        mappedData!!
    }

    // Set once, from any thread, when the fields left out at startup arrive; see [complete].
    @Ignore
    @Volatile
    private var allData: Map<String, String>? = null

    /** The item's fields; after a startup load, only those the list needs until [hasAllFields]. */
    val data: Map<String, String>
        get() = allData ?: loadedData

    /**
     * False for an item loaded with the list's fields only (startup: a regular item's
     * ZoteroDatabase.LIST_FIELDS, an attachment's all but its note); the rest follow in the
     * background (ZoteroDB.loadItemsFromDatabase), or at once for an item that is opened
     * (ZoteroDB.completeItem). Notes and items loaded any other way have all their fields.
     */
    @Ignore
    @Volatile
    var hasAllFields: Boolean = true

    /** Adds the fields left out at load; readers see the old or the complete map, never half. */
    fun complete(rest: List<ItemData>) {
        if (hasAllFields) return
        allData = HashMap(loadedData).apply { for (d in rest) put(d.name, d.value) }
        hasAllFields = true
    }

    /** Replaces the fields after an edit on this device (ItemEditStore); needs [hasAllFields]. */
    fun replaceData(fields: Map<String, String>) {
        check(hasAllFields) { "editing $itemKey before its fields have loaded" }
        allData = HashMap(fields)
    }

    private fun stripHtml(html: String): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            return Html.fromHtml(html).toString()
        }
    }

    fun getTitle(): String {
        val title = when (itemType) {
            "case" -> this.data["caseName"]
            "statute" -> this.data["nameOfAct"]
            "note" -> {
                var noteHtml = this.data["note"]
                stripHtml(noteHtml ?: "unknown")
            }
            else -> this.data["title"]
        }

        // blank fields aren't stored, so a missing title is a blank one
        return title ?: ""
    }

    fun getAuthor(): String {
        return when (creators.size) {
            0 -> ""
            1 -> creators[0].lastName
            else -> "${getSortedCreators()[0].lastName} et al."
        }
    }

    fun getSortableDateString(): String {
        val date = data["date"] ?: ""
        if (date == "") {
            return "ZZZZ"
        }
        return date
    }

    fun getSortableDateAddedString(): String {
        return data["dateAdded"] ?: "XXXX-XX-XX"
    }

    fun getMd5Key(): String {
        return getItemData("md5") ?: ""
    }

    fun getTagList(): List<String> {
        return tags.map { it.tag }
    }

    fun hasParent(): Boolean {
        return this.data.containsKey("parentItem")
    }

    fun getMtime(): Long {
        if (data.containsKey("mtime")) {
            return data["mtime"]!!.toLong()
        }
        Log.e("zotero", "no mtime available for ${itemKey}")
        return 0L
    }

    fun isDownloadable(): Boolean {
        /*Returns whether should be able to download this attachment and open it*/
        if (this.itemType != "attachment") {
            return false
        }

        if (this.data.containsKey("contentType")) {
            return (this.getFileExtension() != "UNKNOWN")
        } else {
            // i don't know, bit of a hack. - basically is returning if attachment and if has extension.
            return (this.getFileExtension() != "UNKNOWN")
        }
        return false
    }

    fun getFileExtension(): String {
        val extension = when (this.data["contentType"]) {
            "application/pdf" -> "pdf"
            "image/vnd.djvu" -> "djvu"
            "application/epub+zip" -> "epub"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/vnd.amazon.ebook" -> "azw"
            "application/vnd.ms-excel" -> "xlsx"
            else -> "UNKNOWN"
        }

        // I probably should have just used file extensions from the beginning...
        if (extension == "UNKNOWN") {
            val filename = if (this.data.containsKey("filename")) {
                this.data["filename"]
            } else {
                this.data["title"]
            }
            return filename?.split(".")?.last() ?: "UNKNOWN"
        }
        return extension
    }

    /** For a PDF attachment: the key of the item that has it (its parent, or itself if standalone). */
    fun pdfOwnerKey(): String? {
        if (!isDownloadable() || data["contentType"] != "application/pdf") return null
        return data["parentItem"] ?: itemKey
    }

    /** For an HTML snapshot (not a link to a web page): the key of the item that has it. */
    fun htmlOwnerKey(): String? {
        if (itemType != "attachment" || data["contentType"] != "text/html" || isWebLink()) return null
        return data["parentItem"] ?: itemKey
    }

    /**
     * For an attachment that is neither a PDF nor an HTML snapshot, nor a link to a web page
     * (an EPUB, an image, a Word file...): the key of the item that has it.
     */
    fun otherAttachmentOwnerKey(): String? {
        if (itemType != "attachment" || isWebLink()) return null
        val type = data["contentType"]
        if (type == "application/pdf" || type == "text/html") return null
        return data["parentItem"] ?: itemKey
    }

    private fun isWebLink() = data["linkMode"] == "linked_url"

    /**
     * Takes [child] (an item whose parentItem is this one) into [attachments] if it is a
     * file that can be opened, or into [notes] if it is a note.
     */
    fun adoptChild(child: Item) {
        if (child.isDownloadable()) attachments.add(child)
        if (child.itemType == "note") {
            try {
                notes.add(Note(child))
            } catch (e: ExceptionInInitializerError) {
                Log.e("zotero", "error loading note ${child.itemKey} error:${e.message}")
            }
        }
    }

    fun getPdfAttachment(): Item? {
        /*
        * If the item has a PDF attachment, this will return it.
        * Otherwise it will return null
        * */

        for (item in attachments) {
            if (item.isDownloadable() && item.getFileExtension() == "pdf") {
                return item
            }
        }
        return null
    }
}

@Entity(
    tableName = "ItemData", primaryKeys = ["parent", "name"],
    foreignKeys = [ForeignKey(
        entity = ItemInfo::class,
        parentColumns = ["itemKey"],
        childColumns = ["parent"],
        onDelete = ForeignKey.CASCADE
    )]
)
class ItemData(
    @ColumnInfo(name = "parent") val parent: String, //itemKey of parent
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "valueType") val valueType: String
)

@Entity(
    tableName = "ItemCreator", primaryKeys = ["parent", "firstName", "lastName", "creatorType"],
    foreignKeys = [ForeignKey(
        entity = ItemInfo::class,
        parentColumns = ["itemKey"],
        childColumns = ["parent"],
        onDelete = ForeignKey.CASCADE
    )]
)

@Parcelize
class Creator(
    @ColumnInfo(name = "parent") val parent: String, //itemKey of parent
    @ColumnInfo(name = "firstName") val firstName: String,
    @ColumnInfo(name = "lastName") val lastName: String,
    @ColumnInfo(name = "creatorType") val creatorType: String,
    @ColumnInfo(name = "order") val order: Int
) : Parcelable {
    fun makeString(): String {
        return "${firstName} ${lastName}"
    }
}

@Entity(
    tableName = "ItemTags", primaryKeys = ["parent", "tag"],
    foreignKeys = [ForeignKey(
        entity = ItemInfo::class,
        parentColumns = ["itemKey"],
        childColumns = ["parent"],
        onDelete = ForeignKey.CASCADE
    )]
)
@Parcelize
data class ItemTag(
    @ColumnInfo(name = "parent") val parent: String, //itemKey of parent
    @ColumnInfo(name = "tag") val tag: String
) : Parcelable

data class KeyAndParent(val itemKey: String, val parent: String?)

data class TagCount(val tag: String, val count: Int)

@Dao
interface ItemDao {
    @Transaction
    @Query("SELECT * FROM itemInfo")
    fun getAll(): Maybe<List<Item>>

    @Transaction
    @Query("SELECT * FROM itemInfo WHERE `itemKey`=:key LIMIT 1")
    fun getItem(key: String): Single<Item>

    @Transaction
    @Query("SELECT * FROM itemInfo WHERE `group`=:groupID and `deleted`=0")
    fun getItemsForGroup(groupID: Int): Maybe<List<Item>>

    @Transaction
    @Query("SELECT * FROM itemInfo WHERE `group`=:groupID and `deleted`=1")
    fun getTrashedItemsForGroup(groupID: Int): Maybe<List<Item>>

    // One query per table for a library's items (not trashed), joined in ZoteroDatabase.loadItems;
    // Room's @Relation for getItemsForGroup reads the same rows in batches of 999 keys.
    // The fields loaded at startup: a note's all, an attachment's all but its note (attachment
    // notes were 7.6 MB of Jim's library and nothing on the list reads them), [names] of other
    // items. t is the row's item type, found through the (parent, name) key.
    @Query(
        "SELECT d.* FROM ItemData d JOIN ItemInfo i ON d.parent = i.itemKey " +
            "JOIN ItemData t ON t.parent = d.parent AND t.name = 'itemType' " +
            "WHERE i.`group`=:groupID AND i.`deleted`=0 AND (d.name IN (:names) OR t.value = 'note' " +
            "OR (t.value = 'attachment' AND d.name <> 'note'))"
    )
    fun getListItemDataForGroup(groupID: Int, names: List<String>): List<ItemData>

    // The rest: what getListItemDataForGroup left out.
    @Query(
        "SELECT d.* FROM ItemData d JOIN ItemInfo i ON d.parent = i.itemKey " +
            "JOIN ItemData t ON t.parent = d.parent AND t.name = 'itemType' " +
            "WHERE i.`group`=:groupID AND i.`deleted`=0 AND d.name NOT IN (:names) AND t.value <> 'note' " +
            "AND (t.value <> 'attachment' OR d.name = 'note')"
    )
    fun getRestOfItemDataForGroup(groupID: Int, names: List<String>): List<ItemData>

    // one item's rest: everything outside [names]; for an attachment that includes fields it
    // already has, which complete() simply sets again
    @Query("SELECT * FROM ItemData WHERE parent = :itemKey AND name NOT IN (:names)")
    fun getRestOfItemData(itemKey: String, names: List<String>): List<ItemData>

    @Query("SELECT * FROM ItemInfo WHERE `group`=:groupID AND `deleted`=0")
    fun getItemInfosForGroup(groupID: Int): List<ItemInfo>

    @Query("SELECT d.* FROM ItemData d JOIN ItemInfo i ON d.parent = i.itemKey WHERE i.`group`=:groupID AND i.`deleted`=0")
    fun getItemDataForGroup(groupID: Int): List<ItemData>

    @Query("SELECT c.* FROM ItemCreator c JOIN ItemInfo i ON c.parent = i.itemKey WHERE i.`group`=:groupID AND i.`deleted`=0")
    fun getCreatorsForGroup(groupID: Int): List<Creator>

    @Query("SELECT t.* FROM ItemTags t JOIN ItemInfo i ON t.parent = i.itemKey WHERE i.`group`=:groupID AND i.`deleted`=0")
    fun getTagsForGroup(groupID: Int): List<ItemTag>

    @Query("SELECT c.* FROM ItemCollection c JOIN ItemInfo i ON c.itemKey = i.itemKey WHERE i.`group`=:groupID AND i.`deleted`=0")
    fun getItemCollectionsForGroup(groupID: Int): List<ItemCollection>

    // Items by key, for the list (RoomItemSource). Callers pass at most
    // ZoteroDatabase.KEY_CHUNK keys: SQLite takes 999 parameters at most.
    @Query("SELECT * FROM ItemInfo WHERE itemKey IN (:keys)")
    fun getItemInfos(keys: List<String>): List<ItemInfo>

    // the fields getListItemDataForGroup reads, for [keys]
    @Query(
        "SELECT d.* FROM ItemData d JOIN ItemInfo i ON d.parent = i.itemKey WHERE d.parent IN (:keys) " +
            "AND (d.name IN (:names) OR i.itemType = 'note' OR (i.itemType = 'attachment' AND d.name <> 'note'))"
    )
    fun getListItemData(keys: List<String>, names: List<String>): List<ItemData>

    @Query("SELECT * FROM ItemData WHERE parent IN (:keys)")
    fun getItemData(keys: List<String>): List<ItemData>

    // [names] of [keys], and the text of those that are notes
    @Query(
        "SELECT d.* FROM ItemData d JOIN ItemInfo i ON d.parent = i.itemKey WHERE d.parent IN (:keys) " +
            "AND (d.name IN (:names) OR (d.name = 'note' AND i.itemType = 'note'))"
    )
    fun getSomeItemData(keys: List<String>, names: List<String>): List<ItemData>

    @Query("SELECT * FROM ItemCreator WHERE parent IN (:keys)")
    fun getCreators(keys: List<String>): List<Creator>

    @Query("SELECT * FROM ItemTags WHERE parent IN (:keys)")
    fun getTags(keys: List<String>): List<ItemTag>

    @Query("SELECT * FROM ItemCollection WHERE itemKey IN (:keys)")
    fun getItemCollections(keys: List<String>): List<ItemCollection>

    // in table order, the order a whole-library load finds them in
    @Query("SELECT itemKey FROM ItemInfo WHERE parent IN (:keys) AND deleted = 0 ORDER BY rowid")
    fun getChildKeys(keys: List<String>): List<String>

    @Query("SELECT itemKey, parent FROM ItemInfo WHERE itemKey IN (:keys) AND deleted = 0")
    fun getParents(keys: List<String>): List<KeyAndParent>

    @Query(
        "UPDATE ItemInfo SET sortTitle = :title, sortAuthor = :author, sortDate = :date, sortDateAdded = :dateAdded, " +
            "searchTitle = :searchTitle, searchCreators = :searchCreators, searchYear = :searchYear, " +
            "searchPublication = :searchPublication, searchAbstract = :searchAbstract, searchTags = :searchTags, " +
            "searchOther = :searchOther WHERE itemKey = :itemKey"
    )
    fun setKeys(
        itemKey: String, title: String, author: String, date: String, dateAdded: String,
        searchTitle: String, searchCreators: String, searchYear: String, searchPublication: String,
        searchAbstract: String?, searchTags: String, searchOther: String,
    )

    @Query("SELECT itemKey FROM ItemInfo WHERE `group` = :groupID AND (sortTitle IS NULL OR searchTitle IS NULL)")
    fun getKeysWithoutKeys(groupID: Int): List<String>

    @Query("SELECT EXISTS (SELECT 1 FROM ItemInfo WHERE `group` = :groupID AND searchTitle IS NULL)")
    fun lacksSearchText(groupID: Int): Boolean

    @Query("SELECT EXISTS (SELECT 1 FROM ItemInfo WHERE `group` = :groupID AND sortTitle IS NULL)")
    fun lacksSortKeys(groupID: Int): Boolean

    @Query("SELECT COUNT(*) FROM ItemInfo WHERE `group` = :groupID AND deleted = 0")
    fun countItems(groupID: Int): Int

    @Query("SELECT itemKey FROM ItemInfo WHERE `group` = :groupID AND deleted = 0 AND parent IS NULL")
    fun getTopLevelKeys(groupID: Int): List<String>

    // each tag on a library's top-level items, with the number of items carrying it
    @Query(
        "SELECT t.tag AS tag, COUNT(DISTINCT t.parent) AS count FROM ItemTags t " +
            "JOIN ItemInfo i ON i.itemKey = t.parent " +
            "WHERE i.`group` = :groupID AND i.deleted = 0 AND i.parent IS NULL GROUP BY t.tag"
    )
    fun getTagCounts(groupID: Int): List<TagCount>

    /** The tags on [keys] (at most 999 of them, SQLite's limit on API 24), each with how many carry it. */
    @Query("SELECT tag, COUNT(DISTINCT parent) AS count FROM ItemTags WHERE parent IN (:keys) GROUP BY tag")
    fun getTagCountsOf(keys: List<String>): List<TagCount>

    @Query("SELECT parent from ItemTags where `tag`=:tag")
    fun getItemKeysWithTag(tag: String): Maybe<List<String>>

    @Transaction
    @Query("DELETE FROM ItemTags WHERE `parent`=:itemKey")
    fun deleteAllTagsForItemKey(itemKey: String): Completable

    @Transaction
    @Query("UPDATE itemInfo SET deleted=1 WHERE `ItemKey`=:itemKey and `group`=:groupID")
    fun moveToTrash(groupID: Int, itemKey: String): Completable

    @Transaction
    @Query("UPDATE itemInfo SET deleted=0 WHERE `ItemKey`=:itemKey and `group`=:groupID")
    fun restoreFromTrash(groupID: Int, itemKey: String): Completable


    @Query("SELECT COUNT(*) != 0 FROM ItemInfo WHERE `itemKey`=:itemKey and `group`=:groupID")
    fun containsItem(groupID: Int, itemKey: String): Single<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItemInfo(items: ItemInfo): Completable

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItemData(itemData: List<ItemData>): Completable

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCreators(creators: List<Creator>): Completable

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItemCollections(collections: List<ItemCollection>): Completable

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTags(tags: List<ItemTag>): Completable


    fun insertItem(
        itemInfo: ItemInfo,
        itemDatas: List<ItemData>,
        creators: List<Creator>,
        collections: List<ItemCollection>,
        tags: List<ItemTag>
    ): Completable {
        return insertItemInfo(itemInfo)
            .andThen(insertItemData(itemDatas))
            .andThen(insertCreators(creators))
            .andThen(insertItemCollections(collections))
            .andThen(insertTags(tags))
    }

//
//    @Update
//    fun updateCollections(vararg item: Item)

    @Delete
    fun delete(item: ItemInfo)

    @Transaction
    @Query("DELETE FROM ItemInfo WHERE `itemKey`=:itemKey")
    fun deleteUsingItemKey(itemKey: String)

    @Query("DELETE FROM iteminfo WHERE `group`=:groupID")
    fun deleteAllForGroup(groupID: Int): Completable

    @Transaction
    @Query("DELETE FROM ItemInfo")
    fun deleteAllItems(): Completable
}