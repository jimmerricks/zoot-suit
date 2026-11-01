package com.mickstarify.zooforzotero

import android.app.Application
import com.mickstarify.zooforzotero.ZoteroStorage.APP_STORAGE_FOLDER
import com.mickstarify.zooforzotero.ZoteroStorage.AppStorageMove
import com.mickstarify.zooforzotero.common.installRxErrorHandler
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import kotlin.concurrent.thread

@HiltAndroidApp
class ZooForZoteroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installRxErrorHandler()
        AppTheme.apply(this)
        // downloads from before 0.5.0 out of shared storage; on at each start until done
        thread(name = "app-storage-move", priority = Thread.MIN_PRIORITY) {
            AppStorageMove(externalCacheDir, File(filesDir, APP_STORAGE_FOLDER)).run()
        }
    }
}
