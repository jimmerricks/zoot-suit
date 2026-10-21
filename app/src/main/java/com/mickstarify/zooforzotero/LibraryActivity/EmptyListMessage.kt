package com.mickstarify.zooforzotero.LibraryActivity

/**
 * What the library list says when it has nothing to show, and whether it offers to clear
 * the filter. The most likely reason comes first: a filter that hides everything, then a
 * search or tag with no results, then the place itself being empty.
 */
data class EmptyListMessage(val text: String, val canClearFilter: Boolean = false) {
    companion object {
        /**
         * @param filter the open library's filter
         * @param filterApplies whether the list went through [filter] (not for Trash and
         *   My Publications)
         * @param query a search ("method") or a tag view ("tag:to-read"); "" for neither
         * @param collection the current place: a collection key or one of the app's
         *   special keys ("all", "group_all", "unfiled_items", "zooforzotero_Trash", …)
         */
        fun forList(
            filter: LibraryFilter,
            filterApplies: Boolean,
            query: String,
            collection: String,
        ): EmptyListMessage = when {
            filterApplies && filter.isActive ->
                EmptyListMessage("No items match the filter: ${filter.describe()}.", canClearFilter = true)
            query.startsWith("tag:") -> EmptyListMessage("No items have the tag “${query.removePrefix("tag:")}”.")
            query.isNotEmpty() -> EmptyListMessage("No items match “$query”.")
            else -> EmptyListMessage(
                when (collection) {
                    "all", "group_all" -> "This library has no items yet. Pull down to sync."
                    "unfiled_items" -> "No unfiled items."
                    "zooforzotero_Trash" -> "The trash is empty."
                    "zooforzotero_my_publications" -> "No items in My Publications."
                    else -> "This collection is empty."
                }
            )
        }
    }
}
