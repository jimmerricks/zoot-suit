package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

class CollectionTreeTest {

    private fun collection(key: String, parent: String = "false", name: String = key) =
        ZoteroCollection(key, 1, name, parent)

    private fun item(key: String) = Item().apply {
        itemInfo = ItemInfo(key, version = 1, deleted = false)
        itemData = emptyList()
        creators = emptyList()
        tags = emptyList()
        collections = emptyList()
    }

    // research ─┬─ method ── interviews
    //           └─ theory
    // books
    private val tree = CollectionTree(
        listOf(
            collection("THEORY", "RESEARCH", "theory"),
            collection("BOOKS", name = "books"),
            collection("INTERVIEWS", "METHOD", "interviews"),
            collection("RESEARCH", name = "Research"),
            collection("METHOD", "RESEARCH", "method"),
        )
    )

    @Test
    fun `roots and children are sorted by name, ignoring case`() {
        assertEquals(listOf("BOOKS", "RESEARCH"), tree.roots.map { it.key })
        assertEquals(listOf("METHOD", "THEORY"), tree.childrenOf("RESEARCH").map { it.key })
        assertTrue(tree.hasChildren("METHOD"))
        assertFalse(tree.hasChildren("INTERVIEWS"))
    }

    @Test
    fun `a collection whose parent is missing counts as top-level`() {
        val orphaned = CollectionTree(listOf(collection("A", "GONE")))
        assertEquals(listOf("A"), orphaned.roots.map { it.key })
    }

    @Test
    fun `ancestors run from the parent up`() {
        assertEquals(listOf("METHOD", "RESEARCH"), tree.ancestorsOf("INTERVIEWS"))
        assertEquals(emptyList<String>(), tree.ancestorsOf("BOOKS"))
    }

    @Test
    fun `walks stop at a cycle`() {
        val cyclic = CollectionTree(listOf(collection("A", "B"), collection("B", "A")))
        assertEquals(listOf("B"), cyclic.ancestorsOf("A"))
        assertEquals(listOf("A", "B"), cyclic.withDescendants("A"))
    }

    @Test
    fun `items from subcollections are included once each`() {
        val shared = item("SHARED")
        val items = mapOf(
            "RESEARCH" to listOf(item("R1"), shared),
            "METHOD" to listOf(shared, item("M1")),
            "INTERVIEWS" to listOf(item("I1")),
            "BOOKS" to listOf(item("B1")),
        )
        val result = tree.itemsIncludingSubcollections("RESEARCH") { items[it] ?: emptyList() }
        assertEquals(listOf("R1", "SHARED", "M1", "I1"), result.map { it.itemKey })
    }

    @Test
    fun `a leaf collection gives just its own items`() {
        val result = tree.itemsIncludingSubcollections("INTERVIEWS") {
            if (it == "INTERVIEWS") listOf(item("I1")) else listOf(item("X"))
        }
        assertEquals(listOf("I1"), result.map { it.itemKey })
    }
}
