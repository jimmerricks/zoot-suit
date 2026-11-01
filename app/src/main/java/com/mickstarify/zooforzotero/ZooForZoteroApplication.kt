package com.mickstarify.zooforzotero

import android.app.Application
import com.mickstarify.zooforzotero.common.installRxErrorHandler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ZooForZoteroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installRxErrorHandler()
        AppTheme.apply(this)
    }
}
