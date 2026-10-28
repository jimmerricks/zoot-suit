package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import java.text.Normalizer
import java.util.Locale

/**
 * The library search. Replaces Item.query, which on every keystroke joined and upper-cased
 * every field of every item (abstracts included) and also returned attachments and child
 * notes as results; on a 20k-item library on the Tab S2 that took seconds.
 *
 * Now each searchable item gets one folded string of the chosen [Field]s, built once per
 * library load ([Index]), and a search is a substring scan over those strings. The query
 * is split into terms ("foucault 1975", or "\"birth of the clinic\"" for a phrase) and an
 * item must contain every term. Case and diacritics are ignored on both sides
 * ("Sörlin" ~ "sorlin").
 */
object LibrarySearch {

    /** What a search looks in; the dialog offers each. Title, creators and year is Zotero's default mode. */
    enum class Field(val label: String, val needsAllFields: Boolean = false) {
        TITLE("Title"),
        CREATORS("Creators"),
        YEAR("Year"),
        // fields a regular item only has once the rest have loaded after startup
        PUBLICATION("Publication and publisher", needsAllFields = true),
        ABSTRACT("Abstract", needsAllFields = true),
        TAGS("Tags"),
        NOTES("Notes"),
        OTHER("Other fields (DOI, ISBN, place, URL, extra, …)", needsAllFields = true),
    }

    val DEFAULT_FIELDS: Set<Field> = setOf(Field.TITLE, Field.CREATORS, Field.YEAR)

    // Zotero field names behind TITLE, PUBLICATION and ABSTRACT; OTHER takes what's left
    // except the bookkeeping fields.
    private val TITLE_FIELDS = setOf("title", "shortTitle", "caseName", "nameOfAct", "note")
    private val PUBLICATION_FIELDS = setOf(
        "publicationTitle", "bookTitle", "proceedingsTitle", "encyclopediaTitle", "dictionaryTitle",
        "websiteTitle", "blogTitle", "forumTitle", "programTitle", "journalAbbreviation",
        "series", "seriesTitle", "publisher", "university", "institution", "conferenceName",
    )
    private val ABSTRACT_FIELDS = setOf("abstractNote")
    private val NOT_SEARCHED = setOf(
        "itemType", "date", "dateAdded", "dateModified", "accessDate", "key", "version",
        "parentItem", "relations", "collections",
    )

    // Between fields, so that a term can't match across the end of one and the start of the next.
    private const val SEP = '\u0001'
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val TERM = Regex("\"([^\"]*)\"|(\\S+)")
    private val YEAR = Regex("\\d{4}")
    private val HTML_TAG = Regex("<[^>]*>")

    /** Lower case without diacritics; ASCII text skips the (slow) Unicode decomposition. */
    fun fold(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.all { it.code < 128 }) return lower
        return COMBINING_MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    /** Folded search terms: words, or "quoted phrases" kept whole. Blank terms are dropped. */
    fun terms(query: String): List<String> = TERM.findAll(query)
        .map { fold(it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /** A note's text without markup; cheaper than Html.fromHtml over thousands of notes. */
    private fun noteText(html: String): String = HTML_TAG.replace(html, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    /** The text [item] is searched by in [fields], folded. */
    fun searchText(item: Item, fields: Set<Field>): String {
        val sb = StringBuilder()
        fun add(s: String?) {
            if (!s.isNullOrEmpty()) sb.append(s).append(SEP)
        }
        if (Field.TITLE in fields) {
            add(if (item.itemType == "note") noteText(item.data["note"] ?: "") else item.getTitle())
            add(item.data["shortTitle"])
        }
        if (Field.CREATORS in fields) {
            for (creator in item.creators) {
                add(creator.firstName)
                add(creator.lastName)
            }
        }
        if (Field.YEAR in fields) add(item.data["date"]?.let { YEAR.find(it)?.value })
        for ((name, value) in item.data) {
            val field = when (name) {
                in TITLE_FIELDS, in NOT_SEARCHED -> null
                in PUBLICATION_FIELDS -> Field.PUBLICATION
                in ABSTRACT_FIELDS -> Field.ABSTRACT
                else -> Field.OTHER
            }
            if (field != null && field in fields) add(value)
        }
        if (Field.TAGS in fields) for (tag in item.tags) add(tag.tag)
        if (Field.NOTES in fields) for (note in item.notes) add(noteText(note.note))
        if (Field.OTHER in fields) add(item.itemKey)
        return fold(sb.toString())
    }

    /**
     * The searchable text of one library's top-level items (child attachments and notes
     * are left out: they are never rows in the list). Immutable; build it again when the
     * library or the fields change, or when the items' remaining fields have loaded
     * ([allFields] false: built before that, for fields that need them), see [isFor].
     */
    class Index(val source: List<Item>, val fields: Set<Field>, val allFields: Boolean = true) {
        private val items: Array<Item>
        private val texts: Array<String>

        init {
            val top = source.filter { !it.hasParent() }
            items = top.toTypedArray()
            texts = Array(top.size) { searchText(top[it], fields) }
        }

        fun isFor(source: List<Item>?, fields: Set<Field>, allFields: Boolean = true) =
            source === this.source && fields == this.fields && allFields == this.allFields

        /** Items containing every term of [query], in library order. No terms, no results. */
        fun search(query: String): List<Item> {
            val terms = terms(query)
            if (terms.isEmpty()) return emptyList()
            val result = ArrayList<Item>()
            for (i in texts.indices) {
                val text = texts[i]
                if (terms.all { text.contains(it) }) result.add(items[i])
            }
            return result
        }
    }

    /**
     * The searchable text of items, by key, without the items: what a list read from the
     * database a query at a time keeps between searches (RoomItemSource).
     */
    class TextIndex(items: List<Item>, fields: Set<Field>) {
        private val keys = Array(items.size) { items[it].itemKey }
        private val texts = Array(items.size) { searchText(items[it], fields) }

        /** Keys of the items containing every term of [query]. No terms, no results. */
        fun search(query: String): Set<String> {
            val terms = terms(query)
            if (terms.isEmpty()) return emptySet()
            val result = HashSet<String>()
            for (i in texts.indices) {
                val text = texts[i]
                if (terms.all { text.contains(it) }) result.add(keys[i])
            }
            return result
        }
    }
}
