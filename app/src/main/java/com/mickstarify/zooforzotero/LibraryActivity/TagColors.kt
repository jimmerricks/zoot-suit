package com.mickstarify.zooforzotero.LibraryActivity

import com.google.gson.JsonParser

/**
 * A library's coloured tags, from Zotero's synced setting "tagColors": a list of
 * {name, color}, where the position in the list is the tag's number (1–9 on desktop).
 * The items list shows these, and any tag containing an emoji, beside an item's title.
 */
class TagColors(val colors: List<Pair<String, String>>) {
    private val positions = colors.withIndex().associate { (i, c) -> c.first to i }
    private val colorByTag = colors.toMap()

    /** [tag]'s number less one, if it is coloured. */
    fun position(tag: String): Int? = positions[tag]

    /** [tag]'s colour ("#RRGGBB"), if it is coloured. */
    fun colorOf(tag: String): String? = colorByTag[tag]

    /** A coloured dot ([color], "#RRGGBB") or an emoji ([emoji]) shown for a tag. */
    data class Swatch(val tag: String, val color: String?, val emoji: String?)

    /**
     * What the items list shows for an item with [tags], in Zotero's order: coloured tags
     * by position, then emoji tags alphabetically. A coloured tag with an emoji shows the
     * emoji, as on desktop.
     */
    fun swatches(tags: Collection<String>): List<Swatch> {
        val shown = tags.distinct().filter { it in colorByTag || containsEmoji(it) }
        if (shown.isEmpty()) return emptyList()
        val ordered = shown.sortedWith(
            compareBy<String> { positions[it] ?: Int.MAX_VALUE }.thenBy { it.lowercase() }
        )
        return ordered.map { tag ->
            val emoji = if (containsEmoji(tag)) extractEmoji(tag) else null
            Swatch(tag, if (emoji == null) colorByTag[tag] else null, emoji)
        }
    }

    companion object {
        val NONE = TagColors(emptyList())

        /** The setting's value as Zotero sends it: a JSON array of {name, color}. */
        fun parse(json: String?): TagColors {
            if (json.isNullOrBlank()) return NONE
            return runCatching {
                TagColors(JsonParser().parse(json).asJsonArray.mapNotNull { entry ->
                    val o = entry.asJsonObject
                    val name = o.get("name")?.asString ?: return@mapNotNull null
                    val color = o.get("color")?.asString ?: return@mapNotNull null
                    name to color
                })
            }.getOrDefault(NONE)
        }

        private const val VARIATION_SELECTOR = 0xFE0F
        private const val ZWJ = 0x200D
        private const val KEYCAP = 0x20E3

        /**
         * Pictographs as Zotero counts them (Utilities.Internal.containsEmoji: emoji,
         * pictographic symbols in ☀–➿ and U+1F000–1FAFF, but not ©®™), without the
         * Unicode property classes Android 7's regex engine lacks.
         */
        private fun isPictograph(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                cp in 0x2B05..0x2B07 || cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55 ||
                cp == 0x231A || cp == 0x231B || cp == 0x2328 || cp == 0x23CF || cp in 0x23E9..0x23F3 ||
                cp in 0x23F8..0x23FA || cp == 0x203C || cp == 0x2049 || cp == 0x3030 || cp == 0x303D ||
                cp == 0x3297 || cp == 0x3299

        private fun codePoints(s: String): IntArray = s.codePoints().toArray()

        fun containsEmoji(s: String): Boolean {
            val cps = codePoints(s)
            return cps.indices.any { i -> isPictograph(cps[i]) || cps.getOrNull(i + 1) == VARIATION_SELECTOR }
        }

        /**
         * The first run of emoji in [s] (Zotero's Tags.extractEmojiForItemsList): "⭐ to
         * read" shows ⭐. Joiners, skin tones and keycaps stay with their emoji.
         */
        fun extractEmoji(s: String): String? {
            val cps = codePoints(s)
            var start = -1
            var i = 0
            while (i < cps.size) {
                val cp = cps[i]
                val partOfRun = isPictograph(cp) || cps.getOrNull(i + 1) == VARIATION_SELECTOR ||
                        (start >= 0 && (cp == VARIATION_SELECTOR || cp == ZWJ || cp == KEYCAP))
                if (partOfRun && start < 0) start = i
                if (!partOfRun && start >= 0) break
                i++
            }
            if (start < 0) return null
            return String(cps, start, i - start)
        }
    }
}
