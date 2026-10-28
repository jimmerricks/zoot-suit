package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The md5 of an attachment's file on this device when it had [size] and [mtime]: what
 * EditedAttachmentFinder last hashed, kept so that a new process doesn't hash every
 * downloaded file again. A file whose size or mtime differs is hashed again.
 *
 * Keyed by item key alone (keys are unique across libraries), and no foreign key to
 * ItemInfo, for the reason AttachmentBase gives.
 */
@Entity(tableName = "FileHash")
class FileHash(
    @PrimaryKey @ColumnInfo(name = "itemKey") val itemKey: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mtime") val mtime: Long,
    @ColumnInfo(name = "md5") val md5: String,
)

@Dao
interface FileHashDao {
    @Query("SELECT * FROM FileHash WHERE itemKey = :itemKey")
    fun get(itemKey: String): FileHash?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(hash: FileHash)

    @Query("DELETE FROM FileHash WHERE itemKey = :itemKey")
    fun delete(itemKey: String)
}
