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

    // the item last tapped or long-pressed in selection mode: where a range starts
    private var anchor: String? = null

    val size: Int get() = keys.size
    fun isEmpty() = keys.isEmpty()
    operator fun contains(key: String) = key in keys

    /** The selected keys, in the order they were chosen. */
    fun keys(): List<String> = keys.toList()

    fun toggle(key: String) {
        active = true
        anchor = key
        if (!keys.remove(key)) keys.add(key)
    }

    /**
     * Gives [key] and every item between it and the one pressed before, both included, in
     * the order of [listed] (the list's keys), the state that item has now: Shift-click on
     * a computer. After ticking an item the range is selected, after unticking one it's
     * unselected. With no item pressed before (or it left the list), just [key] is
     * selected. The range's end is where the next one starts, in the same state.
     */
    fun selectRange(key: String, listed: List<String>) {
        active = true
        val start = anchor
        val from = start?.let { listed.indexOf(it) } ?: -1
        val to = listed.indexOf(key)
        if (start == null || from < 0 || to < 0) {
            keys.add(key)
        } else {
            val range = listed.subList(minOf(from, to), maxOf(from, to) + 1)
            if (start in keys) keys.addAll(range) else keys.removeAll(range.toSet())
        }
        anchor = key
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
    fun clear() {
        keys.clear()
        anchor = null
    }

    /** Leaves selection mode, unselecting everything. */
    fun end() {
        clear()
        active = false
    }

    /** Drops keys not in [listed]: the list changed (another place, search, filter, a sync). */
    fun retainIn(listed: List<String>) {
        if (keys.isEmpty() && anchor == null) return
        val shown = listed.toHashSet()
        keys.retainAll(shown)
        if (anchor !in shown) anchor = null
    }
}
