package com.mickstarify.zooforzotero.SyncSetup.domain

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.mickstarify.zooforzotero.MainActivity
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import java.io.File

/**
 * Signing out leaves the device as a fresh install would, except for attachment files in a
 * folder the user chose (Custom Path), which are theirs. Gone: the login, every library's
 * data and sync versions, the settings (WebDAV login included) and the app's own attachment
 * cache. The process then restarts into sign-in, so nothing of the old account survives in
 * memory (the API client, loaded libraries, singletons).
 */
object SignOut {
    fun signOut(activity: Activity, database: ZoteroDatabase) {
        database.deleteEverything()

        // login (credentials), library versions (one file per library), settings
        val prefsDir = File(activity.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.map { it.name.removeSuffix(".xml") }?.forEach { name ->
            // commit, not apply: the process exits before a background write would run
            activity.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
            activity.deleteSharedPreferences(name)
        }

        // downloaded attachments in the default location, plus temporary files
        activity.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        activity.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        val restart = Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java))
        activity.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }
}
