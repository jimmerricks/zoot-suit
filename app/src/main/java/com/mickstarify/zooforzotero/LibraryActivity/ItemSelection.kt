package com.mickstarify.zooforzotero.LibraryActivity

/**
 * The items chosen in the list for a bulk action, by key: positions shift as pages load,
 * a sync arrives or the sort changes, keys don't. Only items in the list on screen are
 * ever selected ([retainIn] when it changes), so an action never reaches an item the user
 * can't see. Kept in the order they were chosen.
 */
class ItemSelection {
    private val keys = LinkedHashSet<String>()

    /**
     * Whether the list is in selection mode: from the first item chosen until [end] (✕,
     * Back). Emptied by [clear] or by unticking each item, it stays, so choosing can
     * start over without long-pressing again.
     */
    var active = false
        private set

    val size: Int get() = keys.size
    fun isEmpty() = keys.isEmpty()
    operator fun contains(key: String) = key in keys

    /** The selected keys, in the order they were chosen. */
    fun keys(): List<String> = keys.toList()

    fun toggle(key: String) {
        active = true
        if (!keys.remove(key)) keys.add(key)
    }

    /** Selects all of [listed], the list's keys; what was selected stays first. */
    fun selectAll(listed: List<String>) {
        active = true
        keys.addAll(listed)
    }

    /** Within [listed], selected becomes unselected and the other way round. */
    fun invert(listed: List<String>) {
        active = true
        val before = keys.toSet()
        keys.clear()
        listed.filterTo(keys) { it !in before }
    }

    /** Unselects everything; selection mode stays. */
    fun clear() = keys.clear()

    /** Leaves selection mode, unselecting everything. */
    fun end() {
        keys.clear()
        active = false
    }

    /** Drops keys not in [listed]: the list changed (another place, search, filter, a sync). */
    fun retainIn(listed: List<String>) {
        if (keys.isEmpty()) return
        keys.retainAll(listed.toHashSet())
    }
}
