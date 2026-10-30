package com.mickstarify.zooforzotero.ZoteroAPI.Syncing

import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroAPI.ItemScope
import com.mickstarify.zooforzotero.ZoteroAPI.LibraryVersionMisMatchException
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPIItemsResponse
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ItemsDownloadProgress
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The order of a sync's item passes, and what each leaves stored. */
class SyncManagerTest {
    // what ZoteroDB keeps in its preferences
    private var itemsVersion = -1
    private var progress: ItemsDownloadProgress? = null
    private val events = mutableListOf<String>()

    private val db = mock<ZoteroDB> {
        on { groupID } doReturn GroupInfo.NO_GROUP_ID
        on { getLibraryVersion() } doAnswer { itemsVersion }
        on { setItemsVersion(any()) } doAnswer { itemsVersion = it.getArgument(0); events += "version $itemsVersion"; Unit }
        on { getDownloadProgress() } doAnswer { progress }
        on { setDownloadProgress(any()) } doAnswer { progress = it.getArgument(0); Unit }
        on { destroyDownloadProgress() } doAnswer { progress = null; Unit }
        on { getLastDeletedItemsCheckVersion() } doAnswer { itemsVersion }
    }
    private val database = mock<ZoteroDatabase> {
        on { writeItemPOJOs(any(), any()) } doReturn Completable.complete()
    }
    private val api = mock<ZoteroAPI> {
        // not started yet when the synchronous items pass ends the sync (the last "open");
        // finishing at once, each would end it again
        on { getCollections(any(), any()) } doReturn Observable.never()
        on { getTrashedItems(any(), any()) } doReturn Observable.never()
    }
    private val listener = mock<OnSyncChangeListener> {
        on { finishLibrarySync(any(), any()) } doAnswer {
            events += "open"
            it.getArgument<() -> Unit>(1)()
        }
        on { showNewItems(any()) } doAnswer { events += "show"; Unit }
        on { showSyncStatus(any()) } doAnswer { statuses += it.getArgument<SyncStatus>(0); Unit }
    }
    private val statuses = mutableListOf<SyncStatus>()
    private var clock = 0L
    private lateinit var sync: SyncManager

    @Before
    fun setUp() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        sync = SyncManager(api, mock<PreferenceManager>(), database, listener, now = { clock })
    }

    @After
    fun tearDown() {
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    private fun page(version: Int, total: Int) = ZoteroAPIItemsResponse(emptyList(), version, total)

    private fun serve(scope: ItemScope, vararg pages: ZoteroAPIItemsResponse) {
        whenever(api.getItems(any(), any(), anyOrNull(), eq(scope))) doAnswer {
            events += "fetch $scope"
            Observable.fromArray(*pages)
        }
    }

    @Test
    fun firstSyncOpensTheLibraryBetweenTopLevelItemsAndChildren() {
        serve(ItemScope.TOP, page(100, 0))
        serve(ItemScope.CHILDREN, page(101, 0)) // changed meanwhile

        sync.startCompleteSync(db)

        // the library opens before the children come, and again once all is in;
        // the list shows the first page of top-level items as soon as it's in
        assertEquals(listOf("fetch TOP", "show", "open", "fetch CHILDREN", "version 100", "open"), events)
        assertEquals(100, itemsVersion) // the first pass's: the next sync fetches what changed since
        assertNull(progress)
        assertFalse(sync.loadingItems)
    }

    @Test
    fun aSyncCutOffAfterTheTopLevelPassResumesWithTheChildren() {
        serve(ItemScope.TOP, page(100, 0))
        whenever(api.getItems(any(), any(), anyOrNull(), eq(ItemScope.CHILDREN))) doReturn
                Observable.error(java.io.IOException("offline"))

        sync.startCompleteSync(db)

        assertEquals(-1, itemsVersion) // not set: a later sync mustn't skip the children
        assertEquals(ItemsDownloadProgress(100, 0, 0, ItemScope.CHILDREN), progress)

        events.clear()
        serve(ItemScope.CHILDREN, page(100, 0))
        sync = SyncManager(api, mock<PreferenceManager>(), database, listener, now = { clock }) // the app restarted
        sync.startCompleteSync(db)
        assertEquals(listOf("open", "fetch CHILDREN", "version 100", "open"), events)
    }

    @Test
    fun aChangedLibraryRestartsOnlyTheChildren() {
        progress = ItemsDownloadProgress(100, 300, 900, ItemScope.CHILDREN)
        var calls = 0
        whenever(api.getItems(any(), any(), anyOrNull(), eq(ItemScope.CHILDREN))) doAnswer {
            events += "fetch CHILDREN from ${it.getArgument<ItemsDownloadProgress?>(2)?.nDownloaded ?: 0}"
            if (calls++ == 0) Observable.error(LibraryVersionMisMatchException("x"))
            else Observable.just(page(105, 0))
        }

        sync.startCompleteSync(db)

        assertEquals(
            listOf("open", "fetch CHILDREN from 300", "open", "fetch CHILDREN from 0", "version 100", "open"),
            events
        )
    }

    @Test
    fun laterSyncsFetchEverythingChangedInOnePass() {
        itemsVersion = 500
        serve(ItemScope.ALL, page(510, 0))

        sync.startCompleteSync(db)

        assertEquals(listOf("fetch ALL", "version 510", "open"), events)
    }

    @Test
    fun theListIsRedrawnEveryTenSecondsWhileChildrenArrive() {
        progress = ItemsDownloadProgress(100, 0, 0, ItemScope.CHILDREN)
        // a page every 6 s
        whenever(api.getItems(any(), any(), anyOrNull(), eq(ItemScope.CHILDREN))) doReturn
                Observable.fromArray(page(100, 400), page(100, 400), page(100, 400), page(100, 400))
                    .doOnNext { clock += 6_000 }

        sync.startCompleteSync(db)

        // at 12 s and 24 s
        assertEquals(listOf("open", "show", "show", "version 100", "open"), events)
    }

    @Test
    fun theChildrenPassIsKnownWhileItRuns() {
        val seen = mutableListOf<Boolean>()
        whenever(api.getItems(any(), any(), anyOrNull(), eq(ItemScope.TOP))) doReturn
                Observable.just(page(100, 0)).doOnNext { seen += sync.isLoadingChildren(GroupInfo.NO_GROUP_ID) }
        whenever(api.getItems(any(), any(), anyOrNull(), eq(ItemScope.CHILDREN))) doReturn
                Observable.just(page(100, 0)).doOnNext {
                    seen += sync.isLoadingChildren(GroupInfo.NO_GROUP_ID)
                    seen += sync.isLoadingChildren(1234) // another library
                }

        sync.startCompleteSync(db)

        assertEquals(listOf(false, true, false), seen)
        assertFalse(sync.isLoadingChildren(GroupInfo.NO_GROUP_ID))
    }

    @Test
    fun aFirstSyncReportsBothItemPassesAndEnds() {
        serve(ItemScope.TOP, page(100, 250), page(100, 250))
        serve(ItemScope.CHILDREN, page(100, 0))

        sync.startCompleteSync(db)

        val first = statuses.first()
        assertTrue(first.firstSync)
        assertEquals(StageState.Phase.WAITING, first.stages[SyncStage.CHILDREN]?.phase)
        // the toolbar's count while top-level items arrive (pages are empty here: 0 each)
        assertTrue(statuses.any { it.itemProgress() == (0 to 250) })
        val last = statuses.last()
        assertFalse(last.running)
        assertEquals(StageState.Phase.DONE, last.stages[SyncStage.ITEMS]?.phase)
        assertEquals(StageState.Phase.DONE, last.stages[SyncStage.CHILDREN]?.phase)
        assertEquals(StageState.Phase.DONE, last.stages[SyncStage.DELETIONS]?.phase)
        assertNull(last.itemProgress())
    }

    @Test
    fun laterSyncsHaveOneItemPass() {
        itemsVersion = 500
        serve(ItemScope.ALL, page(510, 0))

        sync.startCompleteSync(db)

        assertFalse(statuses.first().firstSync)
        assertEquals(
            listOf(SyncStage.ITEMS, SyncStage.COLLECTIONS, SyncStage.TRASH, SyncStage.DELETIONS),
            statuses.first().ordered().map { it.first }
        )
    }

    @Test
    fun aStageCutOffOfflineSaysSo() {
        itemsVersion = 500
        whenever(api.getItems(any(), any(), anyOrNull(), eq(ItemScope.ALL))) doReturn
                Observable.error(java.net.UnknownHostException("api.zotero.org"))

        sync.startCompleteSync(db)

        val items = statuses.last().stages[SyncStage.ITEMS]
        assertEquals(StageState.Phase.FAILED, items?.phase)
        assertEquals("offline", items?.error)
    }
}
