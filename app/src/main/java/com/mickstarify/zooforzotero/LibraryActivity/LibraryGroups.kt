package com.mickstarify.zooforzotero.LibraryActivity

import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroAPI.GROUP_LIST_LIMIT
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.MaybeObserver
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * The group libraries listed in the sidebar: those on this device, then Zotero's current
 * list. Opening and syncing a group is LibrarySync's.
 *
 * @param api the API client, built on first use (see LibraryComponents)
 */
class LibraryGroups(
    private val ui: LibraryUi,
    private val api: () -> ZoteroAPI,
    private val zoteroDatabase: ZoteroDatabase,
    private val preferences: PreferenceManager,
) {
    private var groups: List<GroupInfo>? = null

    /** Forgets the listed groups (Settings turned group libraries off). */
    fun clear() {
        groups = emptyList()
    }

    fun getGroupById(groupId: Int): GroupInfo? = groups?.firstOrNull { it.id == groupId }

    fun getGroups(): List<GroupInfo> = groups ?: emptyList()

    fun displayGroupsOnUI() {
        val groups = zoteroDatabase.getGroups()
        groups.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .unsubscribeOn(Schedulers.io())
            .subscribe(object : MaybeObserver<List<GroupInfo>> {
                override fun onSuccess(groupInfo: List<GroupInfo>) {
                    Log.d("zotero", "completed get group info.")
                    this@LibraryGroups.groups = groupInfo
                    ui.showGroups()
                }

                override fun onComplete() {
                    Log.d("zotero", "User has no groups.")
                    this@LibraryGroups.groups = emptyList()
                    ui.showGroups()
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "error loading groups.")
                    ui.createErrorAlert(
                        "Couldn't load the group list",
                        "Details: ${e.message}",
                        {})
                }

            })
    }

    /**
     * Lists the groups in the sidebar: first the ones on this device (so they're there
     * offline), then Zotero's current list, which also drops groups the user has left.
     * It does not deal with getting items or collections.
     */
    fun loadGroups() {
        if (!preferences.isGroupLibrarySyncEnabled()) {
            Log.d("zotero", "group library sync disabled in settings; not loading groups")
            return
        }
        displayGroupsOnUI()

        val groupsObserver = api().getGroupInfo()
        groupsObserver.subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .unsubscribeOn(Schedulers.io())
            .subscribe(object : Observer<List<GroupInfo>> {
                override fun onComplete() {
                    Log.d("zotero", "completed getting group info")
                    displayGroupsOnUI()
                }

                override fun onSubscribe(d: Disposable) {
                    Log.d("zotero", "subscribed to group info")
                }

                override fun onNext(groupInfo: List<GroupInfo>) {
                    groupInfo.forEach {
                        val status = zoteroDatabase.addGroup(it)
                        status.blockingAwait() // wait until the db add is finished.
                    }
                    // a full page may not be the whole list; then forget nothing
                    if (groupInfo.size >= GROUP_LIST_LIMIT) return
                    val current = groupInfo.map { it.id }.toSet()
                    zoteroDatabase.getGroups().blockingGet()
                        ?.filter { it.id !in current }
                        ?.forEach { zoteroDatabase.deleteGroup(it) }
                }

                override fun onError(e: Throwable) {
                    // offline or refused: the groups on the device stay listed
                    Log.e("zotero", "could not refresh the group list: $e")
                }

            })
    }
}
