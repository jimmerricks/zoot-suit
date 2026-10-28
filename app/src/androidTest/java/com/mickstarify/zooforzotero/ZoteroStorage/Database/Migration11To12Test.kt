package com.mickstarify.zooforzotero.ZoteroStorage.Database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Migration11To12Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZoteroRoomDatabase::class.java
    )

    @Test
    fun addsAnEmptyFileHashTable() {
        helper.createDatabase("migration-test-12", 11).apply {
            execSQL("INSERT INTO AttachmentBase (itemKey, `group`, md5, recordedAt) VALUES ('PDF1', -1, 'abc', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test-12", 12, true, MIGRATION_11_12)
        assertEquals(0, db.query("SELECT COUNT(*) FROM FileHash").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.query("SELECT COUNT(*) FROM AttachmentBase").use { it.moveToFirst(); it.getInt(0) })
        db.execSQL("INSERT INTO FileHash (itemKey, size, mtime, md5) VALUES ('PDF1', 10, 2, 'abc')")
    }
}
