package com.mickstarify.zooforzotero.ZoteroAPI.Syncing

import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStatusTest {
    private fun status(vararg stages: Pair<SyncStage, StageState>) = SyncStatus(-1, true, mapOf(*stages))

    @Test
    fun withoutCountsTheFirstSyncsPassesTakeHalfEach() {
        val topLevel = status(SyncStage.ITEMS to StageState(Phase.RUNNING, 1_100, 10_306), SyncStage.CHILDREN to StageState())
        assertEquals(5, topLevel.percent())
        val topDone = status(SyncStage.ITEMS to StageState(Phase.RUNNING, 10_306, 10_306), SyncStage.CHILDREN to StageState())
        assertEquals(50, topDone.percent())
        val childrenStart = status(SyncStage.ITEMS to StageState(Phase.DONE, 10_306, 10_306), SyncStage.CHILDREN to StageState(Phase.RUNNING, 100, 8_007))
        assertEquals(50, childrenStart.percent()) // not back to 1
        val children = status(SyncStage.ITEMS to StageState(Phase.DONE, 10_306, 10_306), SyncStage.CHILDREN to StageState(Phase.RUNNING, 4_004, 8_007))
        assertEquals(75, children.percent())
    }

    @Test
    fun knownTotalsWeighThePassesBySize() {
        // 10,306 top-level items and 8,007 attachments and notes, counted at the start
        fun status(vararg stages: Pair<SyncStage, StageState>) = SyncStatus(-1, true, mapOf(*stages), counted = true)
        val topLevel = status(SyncStage.ITEMS to StageState(Phase.RUNNING, 5_000, 10_306), SyncStage.CHILDREN to StageState(total = 8_007))
        assertEquals(27, topLevel.percent())
        val topDone = status(SyncStage.ITEMS to StageState(Phase.DONE, 10_306, 10_306), SyncStage.CHILDREN to StageState(Phase.RUNNING, 0, 8_007))
        assertEquals(56, topDone.percent())
        val children = status(SyncStage.ITEMS to StageState(Phase.DONE, 10_306, 10_306), SyncStage.CHILDREN to StageState(Phase.RUNNING, 4_000, 8_007))
        assertEquals(78, children.percent())
        // resumed at the children: the top-level pass is done though it received nothing now
        val resumed = status(SyncStage.ITEMS to StageState(Phase.DONE, 0, 10_306), SyncStage.CHILDREN to StageState(Phase.RUNNING, 4_000, 8_007))
        assertEquals(78, resumed.percent())
    }

    @Test
    fun aLaterSyncIsOnePass() {
        assertEquals(10, SyncStatus(-1, false, mapOf(SyncStage.ITEMS to StageState(Phase.RUNNING, 1_100, 10_306))).percent())
    }

    @Test
    fun itReaches100OnlyWhenThePassIsComplete() {
        assertEquals(99, status(SyncStage.ITEMS to StageState(Phase.RUNNING, 10_305, 10_306)).percent())
    }

    @Test
    fun noPercentWithoutACount() {
        assertNull(status(SyncStage.ITEMS to StageState(Phase.RUNNING)).percent())
        assertNull(status(SyncStage.DELETIONS to StageState(Phase.RUNNING)).percent())
    }
}
