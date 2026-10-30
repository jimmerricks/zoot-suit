package com.mickstarify.zooforzotero.LibraryActivity.ItemView

import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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

    // Zotero's timestamps, all UTC: dateAdded/dateModified ISO 8601, accessDate SQL style
    private val TIMESTAMP_FIELDS = setOf("dateAdded", "dateModified", "accessDate")
    private val TIMESTAMP_PATTERNS = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss")

    /**
     * [value] of field [key] as shown: a timestamp in the reader's time zone and date
     * format, as Zotero desktop shows it, rather than "2026-10-30T01:23:36Z"; anything else
     * (and a timestamp that doesn't parse, such as an access date without a time) as is.
     */
    fun value(
        key: String,
        value: String,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (key !in TIMESTAMP_FIELDS) return value
        for (pattern in TIMESTAMP_PATTERNS) {
            val parser = SimpleDateFormat(pattern, Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val date = try {
                parser.parse(value)
            } catch (e: ParseException) {
                null
            } ?: continue
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                .apply { timeZone = zone }.format(date)
        }
        return value
    }

    /** A creator as Zotero lists one: "Last, First", or the single name of a one-field creator. */
    fun creatorName(firstName: String?, lastName: String?): String =
        listOfNotNull(lastName, firstName).filter { it.isNotBlank() }.joinToString(", ")
}
