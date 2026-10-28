package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val TABLE_NAME = "Collections"
        database.execSQL("CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` (`key` TEXT NOT NULL, `version` INTEGER NOT NULL, `name` TEXT NOT NULL, `parentCollection` TEXT NOT NULL, `group` INTEGER NOT NULL, PRIMARY KEY(`key`))")

        database.execSQL("CREATE TABLE IF NOT EXISTS `RecentlyOpenedAttachment` (`itemKey` TEXT NOT NULL, PRIMARY KEY(`itemKey`))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS `ItemInfo` (`itemKey` TEXT NOT NULL, `group` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`itemKey`, `group`))")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ItemInfo_itemKey` ON `ItemInfo` (`itemKey`)")
        database.execSQL("CREATE TABLE IF NOT EXISTS `ItemData` (`parent` TEXT NOT NULL, `name` TEXT NOT NULL, `value` TEXT NOT NULL, `valueType` TEXT NOT NULL, PRIMARY KEY(`parent`, `name`), FOREIGN KEY(`parent`) REFERENCES `ItemInfo`(`itemKey`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        database.execSQL("CREATE TABLE IF NOT EXISTS `ItemCreator` (`parent` TEXT NOT NULL, `firstName` TEXT NOT NULL, `lastName` TEXT NOT NULL, `creatorType` TEXT NOT NULL, PRIMARY KEY(`parent`, `firstName`, `lastName`), FOREIGN KEY(`parent`) REFERENCES `ItemInfo`(`itemKey`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        database.execSQL("CREATE TABLE IF NOT EXISTS `ItemTags` (`parent` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`parent`, `tag`), FOREIGN KEY(`parent`) REFERENCES `ItemInfo`(`itemKey`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        database.execSQL("CREATE TABLE IF NOT EXISTS `ItemCollection` (`collectionKey` TEXT NOT NULL, `itemKey` TEXT NOT NULL, PRIMARY KEY(`collectionKey`, `itemKey`), FOREIGN KEY(`itemKey`) REFERENCES `ItemInfo`(`itemKey`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        database.execSQL("CREATE TABLE IF NOT EXISTS `AttachmentInfo` (`itemKey` TEXT NOT NULL, `group` INTEGER NOT NULL, `md5Key` TEXT NOT NULL, `mtime` INTEGER NOT NULL, `downloadedFrom` TEXT NOT NULL, PRIMARY KEY(`itemKey`, `group`), FOREIGN KEY(`itemKey`) REFERENCES `ItemInfo`(`itemKey`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        database.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0edf267c734aa113781c16954559eff8')")

        // delete all old rows from the database (neccessary because i am upgrading the table to add a version record)
        database.execSQL("DELETE FROM `RecentlyOpenedAttachment`")
        database.execSQL("ALTER TABLE `RecentlyOpenedAttachment` ADD `version` INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3,4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ItemInfo` ADD `deleted` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ItemCollection_itemKey_collectionKey` ON `ItemCollection` (`itemKey`, `collectionKey`)")
    }

}

val MIGRATION_4_5 = object: Migration(4,5){
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ItemCreator` ADD `order` INTEGER NOT NULL DEFAULT -1")
    }
}

val MIGRATION_5_6 = object: Migration(5,6){
    override fun migrate(database: SupportSQLiteDatabase) {
        // Fix creator primary key to include creatorType so same person can have multiple roles
        // Create new table with correct primary key structure
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `ItemCreator_new` (
                `parent` TEXT NOT NULL, 
                `firstName` TEXT NOT NULL, 
                `lastName` TEXT NOT NULL, 
                `creatorType` TEXT NOT NULL, 
                `order` INTEGER NOT NULL, 
                PRIMARY KEY(`parent`, `firstName`, `lastName`, `creatorType`), 
                FOREIGN KEY(`parent`) REFERENCES `ItemInfo`(`itemKey`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        
        // Copy data from old table to new table
        database.execSQL("""
            INSERT INTO ItemCreator_new (parent, firstName, lastName, creatorType, `order`) 
            SELECT parent, firstName, lastName, creatorType, `order` FROM ItemCreator
        """.trimIndent())
        
        // Drop old table
        database.execSQL("DROP TABLE ItemCreator")
        
        // Rename new table
        database.execSQL("ALTER TABLE ItemCreator_new RENAME TO ItemCreator")
    }
}

/** Adds AttachmentBase (see AttachmentBase.kt). No foreign key, on purpose. */
/**
 * Drops empty fields. Zotero sends every field of an item type, blank or not: in a 22k-item
 * library 47% of ItemData rows were "", and loading them was most of the startup time.
 * writeItem skips them from now on; nothing reads a blank field differently from a missing one.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DELETE FROM `ItemData` WHERE `value` = ''")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `AttachmentBase` (`itemKey` TEXT NOT NULL, " +
                    "`group` INTEGER NOT NULL, `md5` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`itemKey`, `group`))"
        )
    }
}

/** Adds PendingItemEdit: metadata edits made on this device, waiting to be uploaded. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `PendingItemEdit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`itemKey` TEXT NOT NULL, `group` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `value` TEXT, `oldValue` TEXT, `createdAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_PendingItemEdit_group_itemKey` ON `PendingItemEdit` (`group`, `itemKey`)"
        )
    }
}

/**
 * Copies each item's parentItem, itemType and contentType into ItemInfo (new columns), and
 * indexes the parent: the list then finds children, attachments and PDFs with ItemInfo
 * alone instead of loading every item.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ItemInfo` ADD COLUMN `parent` TEXT")
        database.execSQL("ALTER TABLE `ItemInfo` ADD COLUMN `itemType` TEXT")
        database.execSQL("ALTER TABLE `ItemInfo` ADD COLUMN `contentType` TEXT")
        for ((column, field) in listOf("parent" to "parentItem", "itemType" to "itemType", "contentType" to "contentType")) {
            database.execSQL(
                "UPDATE `ItemInfo` SET `$column` = (SELECT `value` FROM `ItemData` " +
                        "WHERE `ItemData`.`parent` = `ItemInfo`.`itemKey` AND `ItemData`.`name` = '$field')"
            )
        }
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ItemInfo_parent` ON `ItemInfo` (`parent`)")
    }
}

/**
 * Adds ItemInfo's sort keys (SortKeys), so that lists are ordered in SQL. They are
 * computed in Kotlin (a note's title is its text without markup), so they start empty;
 * ZoteroDatabase.fillMissingSortKeys fills them in when a library opens, and until then
 * the list sorts as before.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        for (column in listOf("sortTitle", "sortAuthor", "sortDate", "sortDateAdded")) {
            database.execSQL("ALTER TABLE `ItemInfo` ADD COLUMN `$column` TEXT")
        }
    }
}
