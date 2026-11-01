package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickTagsTest {
    private val colors = TagColors.parse(
        """[{"name":"to read","color":"#FF6666"},{"name":"cited","color":"#2EA8E5"},{"name":"⭐ key","color":"#FFD400"}]"""
    )

    @Test
    fun `every coloured tag in its numbered order`() {
        assertEquals(listOf("to read", "cited", "⭐ key"), QuickTags.offered(colors, emptySet()).map { it.first })
    }

    @Test
    fun `tags the item already has are left out`() {
        assertEquals(listOf("cited"), QuickTags.offered(colors, setOf("to read", "⭐ key", "theory")).map { it.first })
    }

    @Test
    fun `nothing without coloured tags`() {
        assertEquals(emptyList<Pair<String, String>>(), QuickTags.offered(TagColors.NONE, emptySet()))
    }
}
