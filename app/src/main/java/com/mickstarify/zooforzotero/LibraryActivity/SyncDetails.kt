package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState.Phase
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStage
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus

/**
 * What the sync details dialog says (tapping "Syncing n/m"): a line per stage of the
 * sync, what comes after it and when the library last finished one. Redrawn as the sync
 * goes on.
 *
 * @param libraryName "My Library" or a group's name, by library id
 * @param lastSynced when the library last finished a sync, in ms; 0 for never
 * @param formatTime a date and time as the device shows them
 */
class SyncDetails(
    private val status: SyncStatus,
    private val libraryName: (Int) -> String,
    private val lastSynced: Long,
    private val formatTime: (Long) -> String,
) {
    val title: String
        get() {
            val name = libraryName(status.groupID)
            return when {
                status.running && status.firstSync -> "Downloading $name"
                status.running -> "Syncing $name"
                status.stages.values.any { it.phase == Phase.FAILED } -> "Sync of $name incomplete"
                else -> "Sync of $name finished"
            }
        }

    val message: String
        get() {
            val lines = status.ordered().map { (stage, state) -> "${label(stage)}: ${describe(stage, state)}" }
            val after = listOfNotNull(
                status.queued?.let { "Next: ${libraryName(it)}" },
                if (status.running && status.firstSync) {
                    "You can use the library meanwhile. Filters, PDF icons and note search fill in " +
                            "as attachments and notes arrive."
                } else null,
                if (lastSynced > 0) "Last finished sync: ${formatTime(lastSynced)}" else "Not synced before",
            )
            return (lines + "" + after).joinToString("\n")
        }

    private fun label(stage: SyncStage) = when (stage) {
        SyncStage.EDITS -> "Your edits"
        SyncStage.ITEMS -> if (status.firstSync) "Items" else "Changed items"
        SyncStage.CHILDREN -> "Attachments and notes"
        SyncStage.COLLECTIONS -> "Collections"
        SyncStage.TRASH -> "Trash"
        SyncStage.DELETIONS -> "Deletions in Zotero"
    }

    private fun describe(stage: SyncStage, state: StageState): String = when (state.phase) {
        Phase.WAITING -> "waiting"
        Phase.RUNNING -> when {
            stage == SyncStage.EDITS -> "sending…"
            state.total > 0 -> "%,d of %,d".format(minOf(state.done, state.total), state.total)
            else -> "in progress…"
        }
        Phase.DONE -> when {
            stage == SyncStage.EDITS -> "sent"
            state.done > 0 -> "done, %,d received".format(state.done)
            else -> "done"
        }
        Phase.UP_TO_DATE -> "up to date"
        Phase.FAILED -> "failed (${state.error ?: "unknown error"})"
    }
}
