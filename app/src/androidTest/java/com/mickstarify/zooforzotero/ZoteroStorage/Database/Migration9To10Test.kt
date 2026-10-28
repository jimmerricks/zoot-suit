package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Migration9To10Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZoteroRoomDatabase::class.java
    )

    @Test
    fun copiesParentTypeAndContentTypeIntoItemInfo() {
        helper.createDatabase("migration-test", 9).apply {
            execSQL("INSERT INTO ItemInfo (itemKey, `group`, version, deleted) VALUES ('BOOK', -1, 1, 0)")
            execSQL("INSERT INTO ItemInfo (itemKey, `group`, version, deleted) VALUES ('PDF', -1, 1, 0)")
            for ((parent, name, value) in listOf(
                Triple("BOOK", "itemType", "book"), Triple("BOOK", "title", "A book"),
                Triple("PDF", "itemType", "attachment"), Triple("PDF", "parentItem", "BOOK"),
                Triple("PDF", "contentType", "application/pdf"),
            )) {
                execSQL(
                    "INSERT INTO ItemData (parent, name, value, valueType) VALUES (?, ?, ?, 'String')",
                    arrayOf(parent, name, value)
                )
            }
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test", 10, true, MIGRATION_9_10)
        val rows = db.query("SELECT itemKey, parent, itemType, contentType FROM ItemInfo ORDER BY itemKey").use { c ->
            generateSequence { if (c.moveToNext()) List(4) { c.getString(it) } else null }.toList()
        }
        assertEquals(
            listOf(
                listOf("BOOK", null, "book", null),
                listOf("PDF", "BOOK", "attachment", "application/pdf"),
            ),
            rows
        )
    }
}
