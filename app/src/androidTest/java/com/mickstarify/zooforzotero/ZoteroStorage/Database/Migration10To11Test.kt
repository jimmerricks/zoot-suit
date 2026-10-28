package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Migration10To11Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZoteroRoomDatabase::class.java
    )

    @Test
    fun addsEmptySortKeys() {
        helper.createDatabase("migration-test-11", 10).apply {
            execSQL("INSERT INTO ItemInfo (itemKey, `group`, version, deleted, itemType) VALUES ('BOOK', -1, 1, 0, 'book')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-11", 11, true, MIGRATION_10_11)
        val row = db.query("SELECT itemKey, itemType, sortTitle, sortAuthor, sortDate, sortDateAdded FROM ItemInfo").use { c ->
            c.moveToFirst()
            List(6) { c.getString(it) }
        }
        assertEquals(listOf("BOOK", "book", null, null, null, null), row)
    }
}
