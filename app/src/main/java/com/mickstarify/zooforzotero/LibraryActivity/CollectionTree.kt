package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import java.util.Locale

/**
 * One library's collections as a tree, built from each collection's parent key. A
 * collection whose parent isn't in the library counts as top-level, so a half-synced
 * tree still shows everything. Every walk is cycle-safe: Zotero forbids cycles, but a
 * sync that moved collections around could leave one in the local copy.
 */
class CollectionTree(collections: List<Collection>) {
    private val byKey: Map<String, Collection> = collections.associateBy { it.key }
    private val children: Map<String, List<Collection>> = collections
        .filter { it.hasParent() && it.getParent() in byKey }
        .groupBy { it.getParent() }
        .mapValues { (_, list) -> list.sortedBy { it.name.lowercase(Locale.ROOT) } }

    val roots: List<Collection> = collections
        .filter { !it.hasParent() || it.getParent() !in byKey }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun childrenOf(key: String): List<Collection> = children[key] ?: emptyList()

    fun hasChildren(key: String): Boolean = children.containsKey(key)

    /** Keys from [key]'s parent up to its top-level ancestor. */
    fun ancestorsOf(key: String): List<String> {
        val result = mutableListOf<String>()
        var current = byKey[key]
        while (current != null && current.hasParent()) {
            val parent = byKey[current.getParent()] ?: break
            if (parent.key == key || parent.key in result) break
            result.add(parent.key)
            current = parent
        }
        return result
    }

    /**
     * Every collection, each once, as the sidebar shows them all expanded: a parent before
     * its children, siblings by name; with its depth (0 for top-level).
     */
    fun flattened(): List<Pair<Collection, Int>> {
        val result = mutableListOf<Pair<Collection, Int>>()
        val seen = HashSet<String>()
        fun visit(collection: Collection, depth: Int) {
            if (!seen.add(collection.key)) return
            result.add(collection to depth)
            childrenOf(collection.key).forEach { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        return result
    }

    /** [key] and every collection below it, each once. */
    fun withDescendants(key: String): List<String> {
        val seen = LinkedHashSet<String>()
        val pending = ArrayDeque(listOf(key))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next)) childrenOf(next).forEach { pending.addLast(it.key) }
        }
        return seen.toList()
    }

    /**
     * The items in [key] and all its subcollections, each item once (an item can be in
     * several), in the order first found. Zotero's "Show Items from Subcollections".
     */
    fun itemsIncludingSubcollections(key: String, itemsIn: (String) -> List<Item>): List<Item> {
        val seen = HashSet<String>()
        return withDescendants(key).flatMap(itemsIn).filter { seen.add(it.itemKey) }
    }
}
