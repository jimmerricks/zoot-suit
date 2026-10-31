package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterDialogTest {
    private val colors = TagColors.parse(
        """[{"name":"to read","color":"#FF6666"},{"name":"cited","color":"#2EA8E5"},{"name":"⭐ key","color":"#FFD400"}]"""
    )

    @Test
    fun `coloured tags first by number, then selected, then the rest by name`() {
        assertEquals(
            listOf("to read", "cited", "⭐ key", "zine", "Affect", "border", "📚 books"),
            LibraryFilterDialog.order(
                listOf("border", "⭐ key", "zine", "cited", "📚 books", "Affect", "to read"),
                selected = setOf("zine"),
                colors = colors,
            ),
        )
    }

    @Test
    fun `without colours the selected tags lead`() {
        assertEquals(
            listOf("cited", "affect", "to read"),
            LibraryFilterDialog.order(listOf("to read", "cited", "affect"), setOf("cited"), TagColors.NONE),
        )
    }
}
