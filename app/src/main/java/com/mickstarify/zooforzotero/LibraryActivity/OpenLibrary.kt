package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroGroupDB
import java.util.LinkedList
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

/**
 * Which library the library screen has open, My Library or a group, and where in it (the
 * navigator's Back stack). Shared by the parts behind the screen (sync, attachments,
 * edits, lists), which act on whatever library is open when they run.
 */
class OpenLibrary(context: Context) {
    private val myLibrary = ZoteroDB(context, groupID = -1)

    /** Each group's ZoteroDB, made when first asked for and kept. */
    val groups = ZoteroGroupDB(context)

    val navigator = LibraryNavigator()
    val state: LibraryModelState get() = navigator.current

    /** The library [db] reads, apart from navigation: they differ while a group is opening. */
    private var groupId: Int = GroupInfo.NO_GROUP_ID

    /** The open library's ZoteroDB. */
    val db: ZoteroDB get() = if (groupId == GroupInfo.NO_GROUP_ID) myLibrary else groups.getGroup(groupId)

    fun useGroup(id: Int) {
        groupId = id
    }

    fun usePersonalLibrary() {
        groupId = GroupInfo.NO_GROUP_ID
    }

    /** Whether the open library is loaded (its collections and attachment records read). */
    fun isLoaded(): Boolean = db.isOpen()

    val collections: List<ZoteroCollection> get() = db.collections ?: LinkedList()

    fun collection(key: String): ZoteroCollection? = db.getCollectionById(key)
}
