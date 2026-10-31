package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/** The list row's second line: Settings → Appearance → Second line. */
enum class ItemSubtitle(val stored: String) {
    CREATOR("creator"),
    CREATOR_YEAR("creator_year"),
    CREATOR_YEAR_PUBLICATION("creator_year_publication");

    /** "Author et al. · 2019 · Journal", leaving out what the item doesn't have. */
    fun of(item: Item): String {
        val parts = mutableListOf(item.getAuthor())
        if (this != CREATOR) parts += year(item.data["date"]) ?: ""
        if (this == CREATOR_YEAR_PUBLICATION) parts += publication(item) ?: ""
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    companion object {
        fun fromStored(stored: String?) = entries.firstOrNull { it.stored == stored } ?: CREATOR

        /**
         * Zotero's fields that map to its base field publicationTitle (each item type has
         * at most one), then those that map to publisher: a book or thesis has no
         * publication, so its publisher or university stands in.
         */
        val PUBLICATION_FIELDS = listOf(
            "publicationTitle", "bookTitle", "proceedingsTitle", "websiteTitle", "blogTitle",
            "forumTitle", "encyclopediaTitle", "dictionaryTitle", "programTitle", "reporter", "code",
        )
        val PUBLISHER_FIELDS = listOf(
            "publisher", "university", "institution", "label", "company", "distributor",
            "network", "studio",
        )

        /** The first four-digit year in Zotero's free-form date ("2019-03-01", "March 2019"). */
        fun year(date: String?): String? =
            date?.let { Regex("(?<!\\d)\\d{4}(?!\\d)").find(it)?.value }

        fun publication(item: Item): String? =
            (PUBLICATION_FIELDS + PUBLISHER_FIELDS).firstNotNullOfOrNull { field ->
                item.data[field]?.takeIf { it.isNotBlank() }
            }
    }
}
