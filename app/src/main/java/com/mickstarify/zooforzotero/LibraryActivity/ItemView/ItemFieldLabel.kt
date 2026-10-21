package com.mickstarify.zooforzotero.LibraryActivity.ItemView

import java.util.Locale

/**
 * Readable names for Zotero's field keys and item types, as Zotero desktop shows them:
 * "accessDate" → "Accessed", "url" → "URL", "journalArticle" → "Journal Article".
 */
object ItemFieldLabel {
    private val known = mapOf(
        "abstractNote" to "Abstract",
        "accessDate" to "Accessed",
        "publicationTitle" to "Publication",
        "dateAdded" to "Date Added",
        "dateModified" to "Modified",
        "url" to "URL",
        "DOI" to "DOI",
        "ISBN" to "ISBN",
        "ISSN" to "ISSN",
    )

    /** A field key or item type in title case, with Zotero's own names where they differ. */
    fun of(key: String): String = known[key] ?: key
        .split(Regex("(?<=[a-z])(?=[A-Z])"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    /** A creator as Zotero lists one: "Last, First", or the single name of a one-field creator. */
    fun creatorName(firstName: String?, lastName: String?): String =
        listOfNotNull(lastName, firstName).filter { it.isNotBlank() }.joinToString(", ")
}
