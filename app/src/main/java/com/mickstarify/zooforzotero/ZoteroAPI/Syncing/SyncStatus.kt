package com.mickstarify.zooforzotero.ZoteroAPI.Syncing

/** The parts of a sync, in the order they're listed (they run partly at once). */
enum class SyncStage {
    /** This device's edits going up, before the download (LibrarySync). */
    EDITS,
    /** Top-level items in a first sync, changed items in later ones. */
    ITEMS,
    /** A first sync's attachments and notes, after the top-level items. */
    CHILDREN,
    COLLECTIONS,
    TRASH,
    DELETIONS,
}

/** Where a stage is; [done] of [total] counts what it received (0 of 0: not known). */
data class StageState(
    val phase: Phase = Phase.WAITING,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    enum class Phase { WAITING, RUNNING, DONE, UP_TO_DATE, FAILED }
}

/**
 * What a sync of library [groupID] is doing, stage by stage: the toolbar's "Syncing n/m"
 * and the details behind it. Only the stages this sync has are in [stages]. Kept after
 * the sync ends ([running] false) so its details can still be read.
 *
 * @param queued the library a sync waits behind this one for, if any
 */
data class SyncStatus(
    val groupID: Int,
    val firstSync: Boolean,
    val stages: Map<SyncStage, StageState>,
    val running: Boolean = true,
    val queued: Int? = null,
) {
    fun with(stage: SyncStage, change: (StageState) -> StageState): SyncStatus =
        copy(stages = stages + (stage to change(stages[stage] ?: StageState())))

    /** The item count of the item pass running now, for the toolbar: done to total. */
    fun itemProgress(): Pair<Int, Int>? = listOf(SyncStage.ITEMS, SyncStage.CHILDREN)
        .mapNotNull { stages[it] }
        .firstOrNull { it.phase == StageState.Phase.RUNNING && it.total > 0 }
        ?.let { minOf(it.done, it.total) to it.total }

    /** Stages in the order they're listed. */
    fun ordered(): List<Pair<SyncStage, StageState>> =
        SyncStage.entries.mapNotNull { stage -> stages[stage]?.let { stage to it } }
}
