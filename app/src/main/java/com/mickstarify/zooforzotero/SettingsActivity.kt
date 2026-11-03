package com.mickstarify.zooforzotero

import android.content.Intent
import com.mickstarify.zooforzotero.common.ConfirmDialog
import com.mickstarify.zooforzotero.common.DialogText
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.mickstarify.zooforzotero.LibraryActivity.WebDAV.WebDAVSetupDialog
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorage
import com.mickstarify.zooforzotero.SyncSetup.domain.SignOut
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.FilesOnDevice
import com.mickstarify.zooforzotero.ZoteroStorage.STORAGE_ACCESS_REQUEST
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var myStorageManager: AttachmentStorageManager

    @Inject
    lateinit var myPreferenceManager: PreferenceManager

    @Inject
    lateinit var authenticationStorage: AuthenticationStorage

    @Inject
    lateinit var zoteroDatabase: ZoteroDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        // white status bar icons: the toolbar colour shows through the status bar
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        
        setupToolbar()
        // the toolbar's AppBarLayout takes the status bar; keep the list above the nav bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings)) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings, SettingsFragment())
            .commit()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    fun openStoragePicker() {
        myStorageManager.askUserForPath(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("zotero", "got back result request=$requestCode result=$resultCode data=$data")
        when (requestCode) {
            STORAGE_ACCESS_REQUEST -> {
                val fragment = supportFragmentManager.findFragmentById(R.id.settings) as? SettingsFragment
                if (resultCode == RESULT_OK && data?.data != null) {
                    myStorageManager.setStorage(data.dataString)
                } else {
                    // no folder chosen: back to app storage, rather than a folder that isn't there
                    fragment?.findPreference<ListPreference>("attachment_sync_location")?.value = "EXTERNAL_CACHE"
                }
                fragment?.showAttachmentFolder()
            }
        }
    }
    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)


            val settings = requireActivity() as SettingsActivity
            findPreference<Preference>("sign_out")?.summary =
                "Signed in as ${settings.authenticationStorage.getUsername()}"
            findPreference<Preference>("about_version")?.summary = About.version

            findPreference<ListPreference>("attachment_sync_location")
                ?.setOnPreferenceChangeListener { pref, newValue ->
                    if (newValue != (pref as ListPreference).value) confirmFolderChange(pref, newValue as String)
                    false // confirmFolderChange sets it
                }

        }

        /**
         * The app's own dialogs for a list or a typed value (SettingDialogs), built like the
         * library's, instead of androidx.preference's.
         */
        override fun onDisplayPreferenceDialog(preference: Preference) {
            when (preference) {
                is ListPreference -> SettingDialogs.choose(
                    requireContext(), preference.dialogTitle ?: preference.title ?: "",
                    preference.entries.toList(), preference.findIndexOfValue(preference.value),
                ) { index ->
                    val value = preference.entryValues[index].toString()
                    if (preference.callChangeListener(value)) preference.value = value
                }
                is EditTextPreference -> SettingDialogs.edit(
                    requireContext(), preference.dialogTitle ?: preference.title ?: "",
                    preference.summary, preference.text ?: "", InputType.TYPE_CLASS_NUMBER,
                    // the only one is the write timeout, in milliseconds
                    check = { if (it.matches(Regex("\\d+"))) null else "Enter a whole number of milliseconds, 0 or more." },
                ) { value ->
                    if (preference.callChangeListener(value)) preference.text = value
                }
                else -> super.onDisplayPreferenceDialog(preference)
            }
        }

        override fun onResume() {
            super.onResume()
            // Set up a listener whenever a key changes
            preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)

            showWebDAV()
            showAttachmentFolder()
        }

        /** The WebDAV switch and the server's address, as saved (the server dialog changes both). */
        private fun showWebDAV() {
            findPreference<SwitchPreference>("use_webdav")?.isChecked =
                preferenceManager.sharedPreferences!!.getBoolean("use_webdav", false)
            val address = (requireActivity() as SettingsActivity).myPreferenceManager.getWebDAVAddress()
            findPreference<Preference>("configure_webdav")?.summary = address.ifEmpty { "Not set up" }
        }

        /** Attachment folder's summary: where files go, and what happens to them on sign-out. */
        fun showAttachmentFolder() {
            val settings = requireActivity() as SettingsActivity
            findPreference<ListPreference>("attachment_sync_location")?.summary =
                settings.myStorageManager.chosenFolderName()?.let {
                    "\u201c$it\u201d, a folder you chose. The files there are yours: removing one " +
                            "deletes it, signing out leaves them."
                } ?: "App storage, which only this app can read. Emptied when you sign out or uninstall the app."
        }

        /**
         * Switching the attachment folder doesn't move the files already downloaded; if
         * there are any, says what becomes of them and asks first.
         */
        private fun confirmFolderChange(pref: ListPreference, newValue: String) {
            val settings = requireActivity() as SettingsActivity
            Thread {
                // attachments only: a folder the user chose can hold other folders too
                val count = runCatching { settings.filesOnDevice.list().attachments.size }.getOrDefault(0)
                val folder = settings.myStorageManager.chosenFolderName()
                settings.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (count == 0) {
                        pref.value = newValue
                        return@runOnUiThread
                    }
                    val files = DialogText.files(count)
                    val message = if (folder == null) {
                        "The $files in app storage aren't moved. The app won't see them any more; " +
                                "they keep taking space until you switch back or sign out. To free " +
                                "the space, cancel and remove them first: Manage attachments in the " +
                                "library's menu."
                    } else {
                        "The $files in \u201c$folder\u201d aren't moved or deleted. The app won't " +
                                "see them any more; switch back to use them again."
                    }
                    ConfirmDialog.show(
                        settings, "Change the attachment folder?", message,
                        main = "Change folder" to { pref.value = newValue },
                    )
                }
            }.start()
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "configure_webdav") {
                WebDAVSetupDialog(requireContext()) { showWebDAV() }.show()
                return true
            } else if (preference.key == "sign_out") {
                (requireActivity() as SettingsActivity).confirmSignOut()
                return true
            } else if (preference.key == "about_version") {
                About.copyVersion(requireContext())
                return true
            } else if (preference.key == "about_report") {
                About.open(requireContext(), About.reportUrl())
                return true
            } else if (preference.key == "about_source") {
                About.open(requireContext(), About.REPO)
                return true
            } else if (preference.key == "about_licence") {
                About.open(requireContext(), About.LICENCE)
                return true
            } else {
                return super.onPreferenceTreeClick(preference)
            }
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            Log.d("zotero", "sharedpreference change ${key}")
            when (key) {
                AppTheme.KEY -> AppTheme.apply(requireContext())
                "attachment_sync_location" -> {
                    if (sharedPreferences?.getString(
                            "attachment_sync_location",
                            "null"
                        ) == "CUSTOM"
                    ) {
                        (this.activity as SettingsActivity).openStoragePicker()
                    } else {
                        showAttachmentFolder()
                    }
                }
                "use_webdav" -> {
                    if (sharedPreferences?.getBoolean("use_webdav", false) == true && !(requireActivity() as SettingsActivity).myPreferenceManager.isWebDAVConfigured()) {
                        Toast.makeText(requireContext(), "Set up the WebDAV server first.", Toast.LENGTH_SHORT).show()
                        sharedPreferences.edit().putBoolean("use_webdav", false).apply()
                        (requireActivity() as SettingsActivity).updateWebDAVPreferenceUI(false)
                    }
                }
            }
        }
    }

    val filesOnDevice by lazy { FilesOnDevice(zoteroDatabase, myStorageManager) }

    /**
     * Asks before signing out, naming the edits not synced yet that signing out would
     * delete; counting them hashes edited files, so it runs in the background first.
     */
    fun confirmSignOut() {
        Thread {
            val unsynced = runCatching { SignOut.unsyncedEdits(zoteroDatabase, myStorageManager) }
                .onFailure { Log.e("zotero", "counting unsynced edits failed: $it") }
                .getOrNull()
            runOnUiThread { if (!isFinishing && !isDestroyed) showSignOutDialog(unsynced) }
        }.start()
    }

    /** [unsynced]: the edits that would be lost, or null if they couldn't be counted. */
    private fun showSignOutDialog(unsynced: SignOut.UnsyncedEdits?) {
        val forgets = "This device forgets your Zotero login, your libraries and the app's " +
                "settings, WebDAV login included. Files in an attachment folder you chose stay there."
        val message = when {
            unsynced == null -> "$forgets\n\nEdits waiting to sync are lost: sync first."
            unsynced.isEmpty -> forgets
            else -> "Not synced yet, and lost if you sign out now:\n" +
                    unsynced.describe().joinToString("\n") { "• $it" } +
                    "\n\nSync first: in the library, tap the sync icon at the top.\n\n$forgets"
        }
        ConfirmDialog.show(
            this, "Sign out?", message,
            main = (if (unsynced?.isEmpty == true) "Sign out" else "Sign out anyway") to { signOut() },
        )
    }

    /**
     * Signs out in the background: clearing the database, waiting for WorkManager and
     * deleting the downloaded files can take seconds, long enough on the main thread for
     * Android's "isn't responding". The process restarts when it's done.
     */
    private fun signOut() {
        AlertDialog.Builder(this)
            .setMessage("Signing out…")
            .setCancelable(false)
            .show()
        Thread { SignOut.signOut(this, zoteroDatabase) }.start()
    }

    private fun updateWebDAVPreferenceUI(flag: Boolean) {
        val fragment = supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        fragment.findPreference<SwitchPreference>("use_webdav")?.isChecked = flag
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
    }
}