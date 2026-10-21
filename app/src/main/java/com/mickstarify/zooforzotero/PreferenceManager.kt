package com.mickstarify.zooforzotero

import com.mickstarify.zooforzotero.LibraryActivity.LibraryFilter
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import javax.inject.Inject


class PreferenceManager @Inject constructor(context: Context) {
    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /*
     * Sort and filter are kept per library: My Library under the keys these settings
     * always had, a group under the same keys with "_group_<id>" appended (libraryKey).
     */

    fun sortMethodToString(method: SortMethod): String {
        return when (method) {
            SortMethod.TITLE -> "TITLE"
            SortMethod.DATE -> "DATE"
            SortMethod.AUTHOR -> "AUTHOR"
            SortMethod.DATE_ADDED -> "DATE_ADDED"
        }
    }

    fun stringToSortMethod(methodString: String): SortMethod {
        return when (methodString) {
            "TITLE" -> SortMethod.TITLE
            "DATE" -> SortMethod.DATE
            "AUTHOR" -> SortMethod.AUTHOR
            "DATE_ADDED" -> SortMethod.DATE_ADDED
            else -> SortMethod.TITLE
        }
    }

    /** A library's sort; a group never sorted yet uses My Library's. */
    fun getSortMethod(groupId: Int): SortMethod {
        val methodString = sharedPreferences.getString(libraryKey("sort_method", groupId), null)
            ?: sharedPreferences.getString("sort_method", "TITLE") ?: "TITLE"
        return stringToSortMethod(methodString)
    }

    fun setSortMethod(groupId: Int, method: SortMethod) {
        sharedPreferences.edit()
            .putString(libraryKey("sort_method", groupId), sortMethodToString(method)).apply()
    }

    /**
     * Ascending is smallest to largest: A–Z, older dates before newer ones. The default.
     * A group never sorted yet uses My Library's order.
     */
    fun isSortedAscendingly(groupId: Int): Boolean {
        val direction = sharedPreferences.getString(libraryKey("SORT_DIRECTION", groupId), null)
            ?: sharedPreferences.getString("SORT_DIRECTION", null)
        return direction != SORT_METHOD_DESCENDING
    }

    fun setSortAscending(groupId: Int, ascending: Boolean) {
        sharedPreferences.edit().putString(
            libraryKey("SORT_DIRECTION", groupId),
            if (ascending) SORT_METHOD_ASCENDING else SORT_METHOD_DESCENDING
        ).apply()
    }

    /** A library's list filter (tags, device state); see LibraryFilter. Empty by default. */
    fun getLibraryFilter(groupId: Int): LibraryFilter {
        fun flag(key: String) = sharedPreferences.getBoolean(libraryKey(key, groupId), false)
        return LibraryFilter(
            tags = sharedPreferences.getStringSet(libraryKey("library_filter_tags", groupId), null)
                ?.toSet() ?: emptySet(),
            onDevice = flag("library_filter_on_device"),
            editedHere = flag("library_filter_edited_here"),
            // the sort dialog's former "only show items with…" options, same keys
            hasPdf = flag("is_showing_only_with_pdfs"),
            hasNotes = flag("is_showing_only_with_notes"),
        )
    }

    fun setLibraryFilter(groupId: Int, filter: LibraryFilter) {
        sharedPreferences.edit()
            .putStringSet(libraryKey("library_filter_tags", groupId), HashSet(filter.tags))
            .putBoolean(libraryKey("library_filter_on_device", groupId), filter.onDevice)
            .putBoolean(libraryKey("library_filter_edited_here", groupId), filter.editedHere)
            .putBoolean(libraryKey("is_showing_only_with_pdfs", groupId), filter.hasPdf)
            .putBoolean(libraryKey("is_showing_only_with_notes", groupId), filter.hasNotes)
            .apply()
    }

    /** Collections expanded in the sidebar, per library (My Library = NO_GROUP_ID). */
    fun getSidebarExpandedCollections(groupId: Int): Set<String> =
        sharedPreferences.getStringSet("sidebar_expanded_collections_$groupId", null)?.toSet()
            ?: emptySet()

    fun setSidebarExpandedCollections(groupId: Int, keys: Set<String>) {
        sharedPreferences.edit()
            .putStringSet("sidebar_expanded_collections_$groupId", HashSet(keys)).apply()
    }

    /** Like Zotero's View → "Show Items from Subcollections"; off by default, as there. */
    fun showSubcollectionItems(): Boolean =
        sharedPreferences.getBoolean("show_subcollection_items", false)

    /** Where the library screen was (library and collection id), to reopen it at startup. */
    fun getLibraryPlace(): Pair<Int, String>? {
        val collection = sharedPreferences.getString("library_place_collection", null) ?: return null
        return Pair(sharedPreferences.getInt("library_place_group", -1), collection)
    }

    fun setLibraryPlace(groupId: Int, collection: String) {
        sharedPreferences.edit()
            .putInt("library_place_group", groupId)
            .putString("library_place_collection", collection)
            .apply()
    }

    /** Whether the sidebar's Group Libraries heading is open. */
    fun isSidebarGroupsExpanded(): Boolean =
        sharedPreferences.getBoolean("sidebar_groups_expanded", true)

    fun setSidebarGroupsExpanded(value: Boolean) {
        sharedPreferences.edit().putBoolean("sidebar_groups_expanded", value).apply()
    }

    /** On by default: Zotero keeps no file history, so this is the only undo for an upload. */
    fun isWebDAVBackupBeforeUploadEnabled(): Boolean {
        return sharedPreferences.getBoolean("webdav_backup_before_upload", true)
    }

    /** Off by default: group sync needs an API key with group access, and a key
     *  without it gets HTTP 403 on every group request. Applied at once on returning
     *  from Settings (LibraryActivityModel.setGroupLibrariesEnabled). */
    fun isGroupLibrarySyncEnabled(): Boolean {
        return sharedPreferences.getBoolean("group_library_sync_enabled", false)
    }

    fun setIsShowingOnlyPdfs(value: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("is_showing_only_with_pdfs", value)
        editor.apply()
    }

    fun setIsShowingOnlyNotes(value: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("is_showing_only_with_notes", value)
        editor.apply()
    }

    fun setWebDAVAuthentication(
        address: String,
        username: String,
        password: String,
    ) {
        // doesn't turn WebDAV on: WebDAVSetup does that once the connection test passes
        val editor = sharedPreferences.edit()
        editor.putString("webdav_address", address)
        editor.putString("webdav_username", username)
        editor.putString("webdav_password", password)
        editor.apply()
    }

    fun destroyWebDAVAuthentication() {
        val editor = sharedPreferences.edit()
        editor.putBoolean("use_webdav", false)
        editor.putString("webdav_address", "")
        editor.putString("webdav_username", "")
        editor.putString("webdav_password", "")
        editor.putBoolean("webdav_allowInsecureSSL", false)
        editor.putBoolean("webdav_verify_ssl", false)
        editor.putBoolean("webdav_add_zotero_to_url", false)
        editor.putString("webdav_auth_mode", "automatic")

        editor.apply()
    }

    fun getWebDAVUsername(): String {
        return sharedPreferences.getString("webdav_username", "") ?: ""
    }

    fun getWebDAVPassword(): String {
        return sharedPreferences.getString("webdav_password", "") ?: ""
    }

    fun getWebDAVAddress(): String {
        return sharedPreferences.getString("webdav_address", "") ?: ""
    }

    fun isWebDAVConfigured(): Boolean {
        return getWebDAVAddress() != ""
    }

    fun isWebDAVEnabled(): Boolean {
        return sharedPreferences.getBoolean("use_webdav", false)
    }

    fun setWebDAVEnabled(state: Boolean) {
        sharedPreferences.edit().apply {
            putBoolean("use_webdav", state)
            commit()
        }
    }

    fun getWebDAVAddZoteroToUrl(): Boolean {
        return sharedPreferences.getBoolean("webdav_add_zotero_to_url", true)
    }

    fun setWebDAVAddZoteroToUrl(flag: Boolean) {
        sharedPreferences.edit {
            this.putBoolean("webdav_add_zotero_to_url", flag)
            apply()
        }
    }

    fun getWebDAVAuthMode(): WebdavAuthMode {
        return when (sharedPreferences.getString("webdav_auth_mode", "automatic")) {
            "automatic" -> WebdavAuthMode.AUTOMATIC
            "basic" -> WebdavAuthMode.BASIC
            "digest" -> WebdavAuthMode.DIGEST
            else -> WebdavAuthMode.AUTOMATIC
        }
    }

    fun setWebDAVAuthMode(authMode: WebdavAuthMode) {
        sharedPreferences.edit {
            this.putString(
                "webdav_auth_mode", when (authMode) {
                    WebdavAuthMode.AUTOMATIC -> "automatic"
                    WebdavAuthMode.DIGEST -> "digest"
                    WebdavAuthMode.BASIC -> "basic"
                }
            )
            apply()
        }
    }

    fun getVerifySSLForWebDAV(): Boolean {
        var default = true

        // this is for legacy reasons.
        // I originally used "allowInsecureSSL" for the preference name but I changed it to
        // verifySSL. new users won't ever execute this path.
        if (!sharedPreferences.contains("webdav_verify_ssl")) {
            default = !sharedPreferences.getBoolean("webdav_allowInsecureSSL", false)
        }

        return sharedPreferences.getBoolean("webdav_verify_ssl", default)
    }

    fun setVerifySSLForWebDAV(value: Boolean) {
        sharedPreferences.edit {
            this.putBoolean("webdav_verify_ssl", value)
            this.apply()
        }
    }

    fun isWebDAVEnabledForGroups(): Boolean {
        return sharedPreferences.getBoolean("use_webdav_shared_libraries", false)
    }

    fun setCustomAttachmentStorage(data: String) {
        val editor = sharedPreferences.edit()
        editor.putString("custom_attachment_storage_location", data)
        editor.apply()
    }

    fun getCustomAttachmentStorageLocation(): String {
        return sharedPreferences.getString("custom_attachment_storage_location", "") ?: ""
    }

    fun getStorageMode(): String {
        return sharedPreferences.getString("attachment_sync_location", "") ?: ""
    }

    fun useExternalCache() {
        /*Sets the attachment storage location as being external cache.
        * For use in case of errors related to the custom location. */
        val editor = sharedPreferences.edit()
        editor.putString("attachment_sync_location", "EXTERNAL_CACHE")
        editor.putString("custom_attachment_storage_location", "")
        editor.apply()
    }

    fun isAttachmentUploadingEnabled(): Boolean {
        return sharedPreferences.getBoolean("attachments_uploading_enabled", true)
    }

    fun shouldLiveSearch(): Boolean {
        /* This preference defines whether library search should update as the user types.
        * This may be problematic for devices with a slow refresh rate or slow IO. */
        return sharedPreferences.getBoolean("should_live_search", true)
    }

    fun hasShownCustomStorageWarning(): Boolean {
        return sharedPreferences.getBoolean("has_shown_custom_storage_warning", false)
    }

    fun setShownCustomStorageWarning(value: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean("has_shown_custom_storage_warning", true)
        editor.apply()
    }

    fun shouldCheckMd5SumBeforeOpening(): Boolean {
        return sharedPreferences.getBoolean("check_md5sum_before_attachment_open", false)
    }

    fun getWebDAVConnectTimeout(): Long {
        return sharedPreferences.getLong("webdav_connect_timeout", 10000L)

    }

    fun setWebDAVConnectTimeout(time: Long) {
        if (time < 0L) {
            Log.e("Zotero", "Error invalid time $time.")
            return
        }

        sharedPreferences.edit {
            this.putLong("webdav_connect_timeout", time)
            this.apply()
        }
    }

    fun getWebDAVReadTimeout(): Long {
        return sharedPreferences.getLong("webdav_read_timeout", 30000L)
    }

    fun setWebDAVReadTimeout(time: Long) {
        if (time < 0L) {
            Log.e("Zotero", "Error invalid time $time.")
            return
        }

        sharedPreferences.edit {
            this.putLong("webdav_read_timeout", time)
            this.apply()
        }
    }

    fun getWebDAVWriteTimeout(): Long {
        return sharedPreferences.getLong("webdav_write_timeout", 30000L)
    }

    fun setWebDAVWriteTimeout(time: Long) {
        if (time < 0L) {
            Log.e("Zotero", "Error invalid time $time.")
            return
        }

        sharedPreferences.edit {
            this.putLong("webdav_write_timeout", time)
            this.apply()
        }
    }

    fun getHttpWriteTimeout(): Long {
        val s = sharedPreferences.getString("http_write_timeout", "60000")
        try {
            return s?.toLong() ?: 60000L
        } catch (e: NumberFormatException) {
            Log.e("zotero", "error parsing http write timeout. $s")
            return 60000L
        }
    }

    companion object {
        val SORT_METHOD_ASCENDING = "ASCENDING"
        val SORT_METHOD_DESCENDING = "DESCENDING"

        /** The key of a per-library setting: [base] for My Library, suffixed for a group. */
        fun libraryKey(base: String, groupId: Int): String =
            if (groupId == GroupInfo.NO_GROUP_ID) base else "${base}_group_$groupId"
    }

}

enum class WebdavAuthMode {
    BASIC,
    DIGEST,
    AUTOMATIC
}

enum class SortMethod {
    TITLE,
    DATE,
    AUTHOR,
    DATE_ADDED
}