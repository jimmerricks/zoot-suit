package com.mickstarify.zooforzotero.ZoteroAPI

interface DeleteItemListener {
    fun success()
    fun failedItemLocked()
    fun failedItemChangedSince()
    fun failed(code: Int)
    /** No answer from Zotero (offline, timeout). */
    fun failedNoConnection(e: Throwable)
}
