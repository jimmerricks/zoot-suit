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
     * Pushes a new state for [collectionName]. The current group is carried
     * over unless [usePersonalLibrary] is true; the filter (e.g. a tag view)
     * is not, since opening a collection leaves it. Does nothing (and returns
     * false) if we are already viewing that collection unfiltered; re-opening
     * the collection under a filter pushes an unfiltered state, so Back
     * returns to the filtered view.
     */
    fun setCollection(collectionName: String, usePersonalLibrary: Boolean = true): Boolean {
        if (current.currentCollection == collectionName && current.filterText == "") {
            return false
        }
        val newState = LibraryModelState()
        if (!usePersonalLibrary) {
            newState.currentGroup = current.currentGroup
        }
        newState.currentCollection = collectionName
        states.push(newState)
        return true
    }

    fun currentCollection(): String = current.currentCollection

    fun isUsingGroup(): Boolean = current.isUsingGroup()

    fun currentGroup(): GroupInfo = current.currentGroup
}
