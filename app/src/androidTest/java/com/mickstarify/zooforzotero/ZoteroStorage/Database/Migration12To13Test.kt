package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Migration12To13Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZoteroRoomDatabase::class.java
    )

    @Test
    fun addsEmptySearchText() {
        helper.createDatabase("migration-test-13", 12).apply {
            execSQL("INSERT INTO ItemInfo (itemKey, `group`, version, deleted, itemType, sortTitle) VALUES ('BOOK', -1, 1, 0, 'book', 'a title')")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-13", 13, true, MIGRATION_12_13)
        val row = db.query(
            "SELECT itemKey, sortTitle, searchTitle, searchCreators, searchYear, searchPublication, " +
                "searchAbstract, searchTags, searchOther FROM ItemInfo"
        ).use { c ->
            c.moveToFirst()
            List(9) { c.getString(it) }
        }
        assertEquals(listOf("BOOK", "a title", null, null, null, null, null, null, null), row)
    }
}
