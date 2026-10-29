package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind

/**
 * A metadata edit made on this device and not yet uploaded (see ItemEdit). Rows are
 * replayed in [id] order. No foreign key to ItemInfo, on purpose: a sync rewrites or
 * deletes items, and the edit must outlive that until it is uploaded or dropped.
 */
@Entity(tableName = "PendingItemEdit", indices = [Index(value = ["group", "itemKey"])])
data class PendingItemEdit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "itemKey") val itemKey: String,
    @ColumnInfo(name = "group") val group: Int,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "value") val value: String?,
    @ColumnInfo(name = "oldValue") val oldValue: String?,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
) {
    /** Null for a kind this version doesn't know. */
    fun toEdit(): ItemEdit? {
        val kind = ItemEditKind.entries.firstOrNull { it.name == kind } ?: return null
        return ItemEdit(kind, name, value, oldValue, id)
    }

    companion object {
        fun of(edit: ItemEdit, itemKey: String, group: Int) = PendingItemEdit(
            itemKey = itemKey, group = group, kind = edit.kind.name, name = edit.name,
            value = edit.value, oldValue = edit.oldValue, createdAt = System.currentTimeMillis()
        )
    }
}

/** Pending edits, and the item rows they change locally. Blocking; call off the main thread. */
@Dao
interface PendingItemEditDao {
    @Query("SELECT * FROM PendingItemEdit WHERE `group` = :group ORDER BY id")
    fun forGroup(group: Int): List<PendingItemEdit>

    @Query("SELECT * FROM PendingItemEdit WHERE `group` = :group AND itemKey = :itemKey ORDER BY id")
    fun forItem(group: Int, itemKey: String): List<PendingItemEdit>

    @Insert
    fun insert(edit: PendingItemEdit): Long

    @Query("DELETE FROM PendingItemEdit WHERE id IN (:ids)")
    fun delete(ids: List<Long>)

    @Query("UPDATE PendingItemEdit SET value = :value WHERE id = :id")
    fun setValue(id: Long, value: String)

    @Query("SELECT COUNT(*) != 0 FROM ItemInfo WHERE itemKey = :itemKey")
    fun hasItem(itemKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putItem(item: ItemInfo)

    /** With its fields, tags and the rest (foreign keys cascade). */
    @Query("DELETE FROM ItemInfo WHERE itemKey = :itemKey")
    fun deleteItem(itemKey: String)

    @Query("SELECT tag FROM ItemTags WHERE parent = :itemKey")
    fun tagsOf(itemKey: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putTag(tag: ItemTag)

    @Query("DELETE FROM ItemTags WHERE parent = :itemKey AND tag = :tag")
    fun deleteTag(itemKey: String, tag: String)

    @Query("SELECT * FROM ItemData WHERE parent = :itemKey")
    fun fieldsOf(itemKey: String): List<ItemData>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putField(field: ItemData)

    @Query("DELETE FROM ItemData WHERE parent = :itemKey AND name = :name")
    fun deleteField(itemKey: String, name: String)
}
