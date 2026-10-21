package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryKeyTest {
    @Test
    fun `My Library keeps the keys it always had`() {
        assertEquals("sort_method", PreferenceManager.libraryKey("sort_method", GroupInfo.NO_GROUP_ID))
    }

    @Test
    fun `each group has its own key`() {
        assertEquals("sort_method_group_42", PreferenceManager.libraryKey("sort_method", 42))
        assertNotEquals(
            PreferenceManager.libraryKey("library_filter_tags", 42),
            PreferenceManager.libraryKey("library_filter_tags", 43),
        )
    }
}
