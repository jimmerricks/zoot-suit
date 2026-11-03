package com.mickstarify.zooforzotero.LibraryActivity

/**
 * What a bulk dialog ("Add and remove tags", "Collections") has been told for the
 * [selected] items: for each name (a tag, or a collection's key), put the items under it
 * all, take them all out, or leave it as it is. [counts]: how many of the items are under
 * each name now. Nothing is changed until the choices are applied.
 */
class BulkChoices(val selected: Int, private val counts: Map<String, Int>) {

    /** Whether a name covers all, some or none of the items: its checkbox. */
    enum class State { ALL, SOME, NONE }

    // ALL or NONE; a name that isn't here stays as it is. In the order they were chosen.
    private val targets = LinkedHashMap<String, State>()

    // names typed in (new tags) that none of the items is under, in the order typed:
    // listed with the others
    private val typed = LinkedHashSet<String>()

    fun count(name: String): Int = counts[name] ?: 0

    /** Where [name] stands on the items now. */
    fun before(name: String): State = when {
        count(name) <= 0 -> State.NONE
        count(name) >= selected -> State.ALL
        else -> State.SOME
    }

    /** Where [name] will stand once applied. */
    fun state(name: String): State = targets[name] ?: before(name)

    fun isChanged(name: String) = name in targets

    /**
     * The next state of [name], as a tap on its checkbox gives it: all, then none, then
     * back to how it was (a name over some of the items goes through all three).
     */
    fun tap(name: String) {
        val next = when (state(name)) {
            State.SOME -> State.ALL
            State.ALL -> State.NONE
            State.NONE -> if (before(name) == State.SOME) State.SOME else State.ALL
        }
        if (next == before(name)) targets.remove(name) else targets[name] = next
    }

    /** Puts all of the items under [name] (a tag typed in, or a coloured one). */
    fun add(name: String) {
        if (count(name) == 0) typed += name
        if (before(name) == State.ALL) targets.remove(name) else targets[name] = State.ALL
    }

    /** The names to list: those over the items, those typed in, and any chosen meanwhile. */
    fun names(): Set<String> = counts.keys + typed + targets.keys

    /** How many items a name's choice changes: those not under it, or under it to be taken out. */
    fun affected(name: String): Int = when (targets[name]) {
        State.ALL -> selected - count(name)
        State.NONE -> count(name)
        else -> 0
    }

    val toAdd: List<String> get() = targets.filterValues { it == State.ALL }.keys.toList()
    val toRemove: List<String> get() = targets.filterValues { it == State.NONE }.keys.toList()
    val hasChanges: Boolean get() = targets.isNotEmpty()

    /** The number of changes applying makes: one per name chosen. */
    val changes: Int get() = targets.size
}
