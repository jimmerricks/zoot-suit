package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState.Phase
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStage
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDetailsTest {
    private fun details(status: SyncStatus, lastSynced: Long = 0) = SyncDetails(
        status,
        libraryName = { if (it == GroupInfo.NO_GROUP_ID) "My Library" else "Reading Group" },
        lastSynced = lastSynced,
        formatTime = { "at $it" },
    )

    @Test
    fun aFirstSyncListsItsStagesInOrder() {
        val status = SyncStatus(
            GroupInfo.NO_GROUP_ID, firstSync = true,
            stages = mapOf(
                SyncStage.DELETIONS to StageState(),
                SyncStage.CHILDREN to StageState(Phase.RUNNING, 200, 800),
                SyncStage.ITEMS to StageState(Phase.DONE, 500, 500),
                SyncStage.COLLECTIONS to StageState(Phase.UP_TO_DATE),
                SyncStage.TRASH to StageState(Phase.FAILED, error = "offline"),
            ),
        )
        val d = details(status)
        assertEquals("Downloading My Library", d.title)
        assertEquals(
            listOf(
                "Items: done, 500 received",
                "Attachments and notes: 200 of 800",
                "Collections: up to date",
                "Trash: failed (offline)",
                "Deletions in Zotero: waiting",
                "",
            ),
            d.message.lines().take(6)
        )
        assertEquals("Not synced before", d.message.lines().last())
    }

    @Test
    fun aLaterSyncNamesWhatComesNextAndTheLastSync() {
        val status = SyncStatus(
            GroupInfo.NO_GROUP_ID, firstSync = false,
            stages = mapOf(
                SyncStage.EDITS to StageState(Phase.DONE),
                SyncStage.ITEMS to StageState(Phase.RUNNING),
            ),
            queued = 42,
        )
        val d = details(status, lastSynced = 7)
        assertEquals("Syncing My Library", d.title)
        assertEquals(
            "Your edits: sent\nChanged items: in progress…\n\nNext: Reading Group\nLast finished sync: at 7",
            d.message
        )
    }

    @Test
    fun anEndedSyncSaysWhetherAllOfItWorked() {
        val ok = SyncStatus(42, false, mapOf(SyncStage.ITEMS to StageState(Phase.UP_TO_DATE)), running = false)
        assertEquals("Sync of Reading Group finished", details(ok).title)
        val failed = ok.with(SyncStage.TRASH) { StageState(Phase.FAILED, error = "offline") }
        assertEquals("Sync of Reading Group incomplete", details(failed).title)
    }
}
