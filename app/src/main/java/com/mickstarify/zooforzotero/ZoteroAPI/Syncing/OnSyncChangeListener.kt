package com.mickstarify.zooforzotero.ZoteroAPI.Syncing

import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB

interface OnSyncChangeListener {
    fun startSyncAnimation(useSmallAnimation: Boolean)
    fun stopSyncAnimation()
    fun createErrorAlert(title: String, message: String, onClick: () -> Unit)
    fun setSyncProgress(progress: Int, total: Int)
    fun makeToastAlert(message: String)
    fun finishLibrarySync(db: ZoteroDB, onFinish: () -> Unit = {})
    /** Items of [db] were written while it's open (a first sync's attachments and notes). */
    fun showNewItems(db: ZoteroDB) {}
    /** What the sync does now, after each change; once more with running false when it ends. */
    fun showSyncStatus(status: SyncStatus) {}
}