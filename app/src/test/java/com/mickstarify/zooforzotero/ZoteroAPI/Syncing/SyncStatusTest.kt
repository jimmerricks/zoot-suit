package com.mickstarify.zooforzotero.ZoteroAPI.Syncing

import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStatusTest {
    private fun status(vararg stages: Pair<SyncStage, StageState>) = SyncStatus(-1, true, mapOf(*stages))

    @Test
    fun thePercentIsThatOfTheItemPassRunning() {
        val topLevel = status(SyncStage.ITEMS to StageState(Phase.RUNNING, 1_100, 10_306), SyncStage.CHILDREN to StageState())
        assertEquals(10, topLevel.percent())
        val children = status(SyncStage.ITEMS to StageState(Phase.DONE, 10_306, 10_306), SyncStage.CHILDREN to StageState(Phase.RUNNING, 4_004, 8_007))
        assertEquals(50, children.percent())
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
