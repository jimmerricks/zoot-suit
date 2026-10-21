package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import java.util.Locale

/** One row of the library screen's sidebar; built by [SidebarRows.build]. */
sealed class SidebarRow {
    /** My Library ([groupId] = [GroupInfo.NO_GROUP_ID]) or a group library. */
    data class Library(
        val groupId: Int, val name: String, val isActive: Boolean, val isSelected: Boolean
    ) : SidebarRow()

    /** A section title; [expanded] is null if the section can't collapse. */
    data class Heading(val title: String, val expanded: Boolean? = null) : SidebarRow()

    data class CollectionEntry(
        val key: String, val name: String, val depth: Int,
        val hasChildren: Boolean, val isExpanded: Boolean, val isSelected: Boolean
    ) : SidebarRow()

    data class View(val view: SidebarView, val isSelected: Boolean) : SidebarRow()

    object Divider : SidebarRow()
}

/** The fixed views under the collections, with the collection id the navigator uses. */
enum class SidebarView(val collectionId: String, val title: String) {
    UNFILED("unfiled_items", "Unfiled Items"),
    MY_PUBLICATIONS("zooforzotero_my_publications", "My Publications"),
    TRASH("zooforzotero_Trash", "Trash"),
}

object SidebarRows {
    const val GROUPS_HEADING = "Group Libraries"

    /**
     * The sidebar, top to bottom: My Library; the groups under a collapsible heading
     * (the open group stays visible when it's collapsed); the active library's
     * collections as a tree, children shown where their parent's key is in [expanded];
     * then Unfiled, My Publications (My Library only; groups have none) and Trash.
     * [activeGroupId] and [currentCollection] say what the list shows; that row is
     * selected.
     */
    fun build(
        groups: List<GroupInfo>,
        groupsExpanded: Boolean,
        activeGroupId: Int,
        currentCollection: String,
        tree: CollectionTree,
        expanded: Set<String>,
    ): List<SidebarRow> {
        val rows = mutableListOf<SidebarRow>()
        val atTop = currentCollection == LibraryNavigator.ALL ||
                currentCollection == LibraryNavigator.GROUP_ALL
        fun library(id: Int, name: String) =
            SidebarRow.Library(id, name, id == activeGroupId, id == activeGroupId && atTop)

        rows.add(library(GroupInfo.NO_GROUP_ID, "My Library"))
        if (groups.isNotEmpty()) {
            rows.add(SidebarRow.Heading(GROUPS_HEADING, expanded = groupsExpanded))
            groups.sortedBy { it.name.lowercase(Locale.ROOT) }
                .filter { groupsExpanded || it.id == activeGroupId }
                .forEach { rows.add(library(it.id, it.name)) }
        }

        if (tree.roots.isNotEmpty()) {
            rows.add(SidebarRow.Divider)
            val groupName = groups.firstOrNull { it.id == activeGroupId }?.name
            rows.add(SidebarRow.Heading(if (groupName == null) "Collections" else "$groupName · Collections"))
            val seen = HashSet<String>()
            fun addTree(collections: List<Collection>, depth: Int) {
                for (collection in collections) {
                    if (!seen.add(collection.key)) continue
                    val hasChildren = tree.hasChildren(collection.key)
                    val isExpanded = hasChildren && collection.key in expanded
                    rows.add(
                        SidebarRow.CollectionEntry(
                            collection.key, collection.name, depth, hasChildren, isExpanded,
                            isSelected = collection.key == currentCollection
                        )
                    )
                    if (isExpanded) addTree(tree.childrenOf(collection.key), depth + 1)
                }
            }
            addTree(tree.roots, 0)
        }

        rows.add(SidebarRow.Divider)
        SidebarView.entries
            .filter { it != SidebarView.MY_PUBLICATIONS || activeGroupId == GroupInfo.NO_GROUP_ID }
            .forEach { rows.add(SidebarRow.View(it, it.collectionId == currentCollection)) }
        return rows
    }
}
