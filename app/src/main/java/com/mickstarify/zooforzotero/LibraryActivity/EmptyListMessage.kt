package com.mickstarify.zooforzotero.LibraryActivity

/**
 * What the library list says when it has nothing to show, and the label of its button
 * that clears the search and filter, if they are to blame (null: no button). The most
 * likely reason comes first: a search or filter that hides everything, then the place
 * itself being empty.
 */
data class EmptyListMessage(val text: String, val clearLabel: String? = null) {
    companion object {
        /**
         * @param search the open library's search; "" for none
         * @param filter the open library's filter
         * @param filterApplies whether the list went through search and filter (not for
         *   Trash and My Publications)
         * @param collection the current place: a collection key or one of the app's
         *   special keys ("all", "group_all", "unfiled_items", "zooforzotero_Trash", …)
         */
        fun forList(
            search: String,
            filter: LibraryFilter,
            filterApplies: Boolean,
            collection: String,
        ): EmptyListMessage {
            val searching = filterApplies && search.isNotEmpty()
            val filtering = filterApplies && filter.isActive
            return when {
                searching && filtering -> EmptyListMessage(
                    "No items match “$search” with the filter: ${filter.describe()}.",
                    "Clear search and filter"
                )
                searching -> EmptyListMessage("No items here match “$search”.", "Clear search")
                filtering -> EmptyListMessage("No items match the filter: ${filter.describe()}.", "Clear filter")
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
}
