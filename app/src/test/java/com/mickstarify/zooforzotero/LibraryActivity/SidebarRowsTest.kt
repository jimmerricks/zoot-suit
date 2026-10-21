package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

class SidebarRowsTest {

    private fun group(id: Int, name: String) = GroupInfo(id, 1, name, "", "", "", "", "", "", -1)
    private val groups = listOf(group(2, "Reading Group"), group(1, "Asap"))

    private val tree = CollectionTree(
        listOf(
            ZoteroCollection("RESEARCH", 1, "research", "false"),
            ZoteroCollection("METHOD", 1, "method", "RESEARCH"),
            ZoteroCollection("BOOKS", 1, "books", "false"),
        )
    )

    private fun build(
        activeGroupId: Int = GroupInfo.NO_GROUP_ID,
        collection: String = "all",
        groupsExpanded: Boolean = true,
        expanded: Set<String> = emptySet(),
        groups: List<GroupInfo> = this.groups,
    ) = SidebarRows.build(groups, groupsExpanded, activeGroupId, collection, tree, expanded)

    /** One short label per row, to compare whole sidebars at a glance. */
    private fun describe(rows: List<SidebarRow>) = rows.map { row ->
        when (row) {
            is SidebarRow.Library -> (if (row.isSelected) "*" else "") + (if (row.isActive) "!" else "") + row.name
            is SidebarRow.Heading -> "# ${row.title}" + when (row.expanded) { true -> " v"; false -> " >"; null -> "" }
            is SidebarRow.CollectionEntry -> " ".repeat(row.depth) + (if (row.isSelected) "*" else "") +
                    row.name + when { !row.hasChildren -> ""; row.isExpanded -> " v"; else -> " >" }
            is SidebarRow.View -> (if (row.isSelected) "*" else "") + row.view.title
            SidebarRow.Divider -> "--"
        }
    }

    @Test
    fun `my library with groups and collapsed collections`() {
        assertEquals(
            listOf(
                "*!My Library", "# Group Libraries v", "Asap", "Reading Group",
                "--", "# Collections", "books", "research >",
                "--", "Unfiled Items", "My Publications", "Trash",
            ),
            describe(build())
        )
    }

    @Test
    fun `an expanded collection shows its children indented`() {
        val rows = describe(build(collection = "METHOD", expanded = setOf("RESEARCH")))
        assertEquals(listOf("books", "research v", " *method"), rows.subList(6, 9))
        assertEquals("!My Library", rows[0])
    }

    @Test
    fun `in a group, its collections are named and there is no My Publications`() {
        val rows = describe(build(activeGroupId = 2, collection = "group_all"))
        assertTrue("*!Reading Group" in rows)
        assertTrue("My Library" in rows)
        assertTrue("# Reading Group · Collections" in rows)
        assertFalse("My Publications" in rows)
        assertTrue("Trash" in rows)
    }

    @Test
    fun `collapsed groups hide all but the open one`() {
        val rows = describe(build(activeGroupId = 2, collection = "BOOKS", groupsExpanded = false))
        assertEquals(listOf("My Library", "# Group Libraries >", "!Reading Group", "--"), rows.subList(0, 4))
        assertTrue("*books" in rows)
    }

    @Test
    fun `no groups means no group heading`() {
        val rows = describe(build(groups = emptyList(), collection = "zooforzotero_Trash"))
        assertFalse(rows.any { it.startsWith("# Group") })
        assertEquals("*Trash", rows.last())
    }
}
