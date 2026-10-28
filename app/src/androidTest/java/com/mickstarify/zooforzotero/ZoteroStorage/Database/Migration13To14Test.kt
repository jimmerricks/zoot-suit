package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.mickstarify.zooforzotero.LibraryActivity.LibrarySearch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Migration13To14Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZoteroRoomDatabase::class.java
    )

    @Test
    fun dropsTheAbstractCopiesLowerReproduces() {
        // as version 13 stored them: the folded abstract and the separator, or "" without one
        val abstracts = linkedMapOf("ASCII" to "An Abstract — “quoted”", "ACCENT" to "Élan vital", "NONE" to null)
        helper.createDatabase("migration-test-14", 13).apply {
            for ((key, abstract) in abstracts) {
                execSQL(
                    "INSERT INTO ItemInfo (itemKey, `group`, version, deleted, searchTitle, searchAbstract) VALUES (?, -1, 1, 0, 'title', ?)",
                    arrayOf(key, abstract?.let { LibrarySearch.fold(it) + '\u0001' } ?: ""),
                )
                if (abstract != null) execSQL("INSERT INTO ItemData (parent, name, value, valueType) VALUES (?, 'abstractNote', ?, 'String')", arrayOf(key, abstract))
            }
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-14", 14, true, MIGRATION_13_14)
        val rows = db.query("SELECT itemKey, searchTitle, searchAbstract FROM ItemInfo ORDER BY itemKey").use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2))) }
        }
        assertEquals(
            listOf(
                listOf("ACCENT", "title", "elan vital\u0001"),
                listOf("ASCII", "title", null),
                listOf("NONE", "title", null),
            ),
            rows,
        )
    }
}
