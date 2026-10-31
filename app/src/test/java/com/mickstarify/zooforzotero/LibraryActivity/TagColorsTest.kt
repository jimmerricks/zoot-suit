package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.TagColors.Swatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagColorsTest {
    private val colors = TagColors.parse(
        """[{"name":"to read","color":"#FF6666"},{"name":"cited","color":"#2EA8E5"},{"name":"⭐ key","color":"#FFD400"}]"""
    )

    @Test
    fun `coloured tags in their numbered order, plain tags left out`() {
        assertEquals(
            listOf(Swatch("to read", "#FF6666", null), Swatch("cited", "#2EA8E5", null)),
            colors.swatches(listOf("theory", "cited", "to read")),
        )
    }

    @Test
    fun `emoji tags show their emoji, after the coloured ones, alphabetically`() {
        assertEquals(
            listOf(
                Swatch("cited", "#2EA8E5", null),
                Swatch("⭐ key", null, "⭐"),
                Swatch("📚 books", null, "📚"),
                Swatch("🔥 hot", null, "🔥"),
            ),
            colors.swatches(listOf("🔥 hot", "⭐ key", "📚 books", "cited")),
        )
    }

    @Test
    fun `no setting or a broken one shows only emoji tags`() {
        assertEquals(emptyList<Swatch>(), TagColors.parse(null).swatches(listOf("to read")))
        assertEquals(emptyList<Swatch>(), TagColors.parse("{not json").swatches(listOf("to read")))
        assertEquals(listOf(Swatch("✅", null, "✅")), TagColors.NONE.swatches(listOf("✅", "done")))
    }

    @Test
    fun `emoji are told apart from other symbols as Zotero does`() {
        assertTrue(TagColors.containsEmoji("☀"))
        assertTrue(TagColors.containsEmoji("read 📖"))
        assertTrue(TagColors.containsEmoji("❤️"))
        assertFalse(TagColors.containsEmoji("© 2020"))
        assertFalse(TagColors.containsEmoji("™"))
        assertFalse(TagColors.containsEmoji("Ödegaard → STS"))
    }

    @Test
    fun `the first run of emoji is shown, joined sequences whole`() {
        assertEquals("⭐", TagColors.extractEmoji("⭐ to read"))
        assertEquals("❤️", TagColors.extractEmoji("❤️ fave"))
        // woman technologist: woman + ZWJ + laptop
        assertEquals("👩‍💻", TagColors.extractEmoji("x 👩‍💻 y"))
        assertEquals("🔥🔥", TagColors.extractEmoji("🔥🔥 then ⭐"))
        assertNull(TagColors.extractEmoji("plain"))
    }
}
