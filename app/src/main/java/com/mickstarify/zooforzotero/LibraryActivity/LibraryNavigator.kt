package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import java.util.Stack

/**
 * Owns the navigation state stack for the library screen.
 *
 * Each [LibraryModelState] on the stack represents a place the user has
 * navigated to (a collection, a group, or a filtered view). Pushing a new
 * state lets the back button restore the previous one. This logic was
 * previously inlined in LibraryActivityModel; it is pure (no Android,
 * network, or UI dependencies) so it can be unit tested directly.
 */
class LibraryNavigator {
    val states = Stack<LibraryModelState>()

    val current: LibraryModelState
        get() = states.peek()

    init {
        // the first state is a junk root state; see loadPriorState().
        states.push(LibraryModelState())
    }

    /**
     * Pushes a new state for [collectionName] in the current library (My Library or a
     * group); the filter (e.g. a tag view) is not carried, since opening a collection
     * leaves it. Does nothing (and returns false) if we are already viewing that
     * collection unfiltered; re-opening the collection under a filter pushes an
     * unfiltered state, so Back returns to the filtered view.
     */
    fun setCollection(collectionName: String): Boolean {
        if (current.currentCollection == collectionName && current.filterText == "") {
            return false
        }
        val newState = LibraryModelState()
        newState.currentGroup = current.currentGroup
        newState.currentCollection = collectionName
        states.push(newState)
        return true
    }

    /**
     * Pushes the top of a library: My Library's "all" when [group] is null, else the
     * group's "group_all". Returns false if that is already what's shown, unfiltered.
     */
    fun openLibrary(group: GroupInfo?): Boolean {
        val newState = LibraryModelState()
        if (group != null) newState.currentGroup = group
        newState.currentCollection = if (group != null) GROUP_ALL else ALL
        if (current.currentGroup.id == newState.currentGroup.id &&
            current.currentCollection == newState.currentCollection && current.filterText == ""
        ) {
            return false
        }
        states.push(newState)
        return true
    }

    fun currentCollection(): String = current.currentCollection

    fun isUsingGroup(): Boolean = current.isUsingGroup()

    fun currentGroup(): GroupInfo = current.currentGroup

    companion object {
        const val ALL = "all"
        const val GROUP_ALL = "group_all"
    }
}
