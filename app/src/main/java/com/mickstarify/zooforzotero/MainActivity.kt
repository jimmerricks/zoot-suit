package com.mickstarify.zooforzotero

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.mickstarify.zooforzotero.LibraryActivity.LibraryActivity
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import com.mickstarify.zooforzotero.SyncSetup.SyncSetupActivity

/**
 * The launcher's entry: opens the library, or sign-in without a stored login. It shows
 * nothing itself (Theme.NoDisplay, no layout), so a launch doesn't inflate and draw a
 * window that is gone at once; a plain Activity, as AppCompat needs a window.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = AuthenticationStorageImpl(this)
        val intent = if (auth.hasCredentials()) {
            Intent(this, LibraryActivity::class.java)
        } else {
            Intent(this, SyncSetupActivity::class.java)
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }
}
