package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The md5 of the attachment file as this device last downloaded or uploaded it: the
 * version any local edits are based on. The pre-upload check compares the server
 * against this, and opening compares it with Zotero's md5 to spot a stale local copy.
 *
 * Deliberately no foreign key to ItemInfo: sync rewrites ItemInfo rows with REPLACE
 * (delete + insert), which would cascade-delete this record exactly when the
 * attachment changes on the server, the moment it's needed. (AttachmentInfo has that
 * cascade, which is why it can't serve here.)
 */
@Entity(tableName = "AttachmentBase", primaryKeys = ["itemKey", "group"])
class AttachmentBase(
    @ColumnInfo(name = "itemKey") val itemKey: String,
    @ColumnInfo(name = "group") val groupParent: Int,
    @ColumnInfo(name = "md5") val md5: String,
    @ColumnInfo(name = "recordedAt") val recordedAt: Long
)

@Dao
interface AttachmentBaseDao {
    @Query("SELECT * FROM AttachmentBase WHERE `itemKey`=:itemKey AND `group`=:group LIMIT 1")
    fun get(itemKey: String, group: Int): AttachmentBase?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(base: AttachmentBase)
}
