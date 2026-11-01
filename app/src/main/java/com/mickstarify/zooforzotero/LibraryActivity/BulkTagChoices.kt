package com.mickstarify.zooforzotero.LibraryActivity

/**
 * What "Add and remove tags" has been told for the [selected] items: for each tag, put it
 * on all of them, take it off all of them, or leave it as it is. [counts]: how many of the
 * items carry each tag now. Nothing is changed until the choices are applied.
 */
class BulkTagChoices(val selected: Int, private val counts: Map<String, Int>) {

    /** Whether a tag is on all, some or none of the items: its checkbox. */
    enum class State { ALL, SOME, NONE }

    // ALL or NONE; a tag that isn't here stays as it is. In the order they were chosen.
    private val targets = LinkedHashMap<String, State>()

    // tags typed in that none of the items carry, in the order typed: listed with the others
    private val typed = LinkedHashSet<String>()

    fun count(tag: String): Int = counts[tag] ?: 0

    /** Where [tag] stands on the items now. */
    fun before(tag: String): State = when {
        count(tag) <= 0 -> State.NONE
        count(tag) >= selected -> State.ALL
        else -> State.SOME
    }

    /** Where [tag] will stand once applied. */
    fun state(tag: String): State = targets[tag] ?: before(tag)

    fun isChanged(tag: String) = tag in targets

    /**
     * The next state of [tag], as a tap on its checkbox gives it: on all, then off all,
     * then back to how it was (a tag on some of the items goes through all three).
     */
    fun tap(tag: String) {
        val next = when (state(tag)) {
            State.SOME -> State.ALL
            State.ALL -> State.NONE
            State.NONE -> if (before(tag) == State.SOME) State.SOME else State.ALL
        }
        if (next == before(tag)) targets.remove(tag) else targets[tag] = next
    }

    /** Puts [tag] (typed in, or a coloured one) on all of the items. */
    fun add(tag: String) {
        if (count(tag) == 0) typed += tag
        if (before(tag) == State.ALL) targets.remove(tag) else targets[tag] = State.ALL
    }

    /** The tags to list: those on the items, those typed in, and any chosen meanwhile. */
    fun tags(): Set<String> = counts.keys + typed + targets.keys

    /** How many items a tag's choice changes: those without it, or with it to be removed. */
    fun affected(tag: String): Int = when (targets[tag]) {
        State.ALL -> selected - count(tag)
        State.NONE -> count(tag)
        else -> 0
    }

    val toAdd: List<String> get() = targets.filterValues { it == State.ALL }.keys.toList()
    val toRemove: List<String> get() = targets.filterValues { it == State.NONE }.keys.toList()
    val hasChanges: Boolean get() = targets.isNotEmpty()
}
