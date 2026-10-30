package com.mickstarify.zooforzotero

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
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
import com.mickstarify.zooforzotero.LibraryActivity.SelectionDialogs
import com.mickstarify.zooforzotero.LibraryActivity.WebDAV.WebDAVSetup
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorage
import com.mickstarify.zooforzotero.SyncSetup.domain.SignOut
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloads
import com.mickstarify.zooforzotero.ZoteroStorage.FilesOnDevice
import com.mickstarify.zooforzotero.ZoteroStorage.STORAGE_ACCESS_REQUEST
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private fun fileCount(n: Int) = if (n == 1) "1 file" else "$n files"

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
                fragment?.showFilesOnDevice()
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

            findPreference<ListPreference>("attachment_sync_location")
                ?.setOnPreferenceChangeListener { pref, newValue ->
                    if (newValue != (pref as ListPreference).value) confirmFolderChange(pref, newValue as String)
                    false // confirmFolderChange sets it
                }

            val httpTimeoutPreference: EditTextPreference? = findPreference("http_write_timeout")

            httpTimeoutPreference?.setOnBindEditTextListener { editText ->
                // Ensure the input allows only integers
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }

            httpTimeoutPreference?.setOnPreferenceChangeListener { _, newValue ->
                // Validate the input to ensure it's a non-negative integer
                val value = newValue.toString()
                val isValid = value.matches(Regex("\\d+")) // Match only non-negative integers
                if (!isValid) {
                    Toast.makeText(context, "Enter a whole number of milliseconds, 0 or more.", Toast.LENGTH_SHORT).show()
                }
                isValid
            }

        }

        override fun onResume() {
            super.onResume()
            // Set up a listener whenever a key changes
            preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)

            this.findPreference<SwitchPreference>("use_webdav")?.isChecked =
                preferenceManager.sharedPreferences!!.getBoolean("use_webdav", false)
            // back from the server screen: show what it saved
            val address = (requireActivity() as SettingsActivity).myPreferenceManager.getWebDAVAddress()
            findPreference<Preference>("configure_webdav")?.summary = address.ifEmpty { "Not set up" }
            showAttachmentFolder()
            showFilesOnDevice()
        }

        /** Attachment folder's summary: where files go, and what happens to them on sign-out. */
        fun showAttachmentFolder() {
            val settings = requireActivity() as SettingsActivity
            findPreference<ListPreference>("attachment_sync_location")?.summary =
                settings.myStorageManager.chosenFolderName()?.let {
                    "\u201c$it\u201d, a folder you chose. The files there are yours: removing one " +
                            "deletes it, signing out leaves them."
                } ?: "App storage. Emptied when you sign out or uninstall the app."
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
                    val files = fileCount(count)
                    val message = if (folder == null) {
                        "The $files in app storage aren't moved. The app won't see them any more; " +
                                "they keep taking space until you switch back or sign out. To free " +
                                "the space, cancel and use Remove all files from this device first."
                    } else {
                        "The $files in \u201c$folder\u201d aren't moved or deleted. The app won't " +
                                "see them any more; switch back to use them again."
                    }
                    AlertDialog.Builder(settings)
                        .setTitle("Change the attachment folder?")
                        .setMessage(message)
                        .setPositiveButton("Change") { _, _ -> pref.value = newValue }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }.start()
        }

        /** "Remove all files"'s summary: how many there are and their size. */
        fun showFilesOnDevice() {
            val settings = requireActivity() as SettingsActivity
            val row = findPreference<Preference>("remove_all_files") ?: return
            row.summary = "Counting…"
            Thread {
                val files = runCatching { settings.filesOnDevice.list() }
                    .onFailure { Log.e("zotero", "listing files on the device failed: $it") }
                    .getOrNull()
                val inFolder = settings.myStorageManager.chosenFolderName()?.let { " in \u201c$it\u201d" } ?: ""
                settings.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    row.summary = when {
                        files == null -> "Removes every downloaded attachment. They stay in Zotero."
                        files.attachments.isEmpty() -> "No files on this device."
                        else -> "${fileCount(files.attachments.size)}, " +
                                "${Formatter.formatShortFileSize(settings, files.bytes)}" +
                                inFolder +
                                ". They stay in Zotero."
                    }
                    row.isEnabled = files == null || files.attachments.isNotEmpty()
                }
            }.start()
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            if (preference.key == "configure_webdav") {
                val intent = Intent(requireContext(), WebDAVSetup::class.java)
                startActivity(intent)
                return true
            } else if (preference.key == "remove_all_files") {
                (requireActivity() as SettingsActivity).confirmRemoveAllFiles()
                return true
            } else if (preference.key == "sign_out") {
                (requireActivity() as SettingsActivity).confirmSignOut()
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
                        showFilesOnDevice()
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
     * Asks before removing every attachment file from the device, as the bulk action does
     * for a selection: names the edited ones, which can be kept. Listing and finding the
     * edited ones hashes changed files, so that runs in the background first.
     */
    fun confirmRemoveAllFiles() {
        Thread {
            val found = runCatching {
                val files = filesOnDevice.list().attachments
                files to filesOnDevice.edited(files)
            }.onFailure { Log.e("zotero", "listing files on the device failed: $it") }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    found == null -> Toast.makeText(this, "Couldn't list the files on this device.", Toast.LENGTH_SHORT).show()
                    found.first.isEmpty() -> Toast.makeText(this, "No files on this device.", Toast.LENGTH_SHORT).show()
                    else -> SelectionDialogs(this).confirmDelete(found.first, found.second, myStorageManager.chosenFolderName(), ::removeFiles)
                }
            }
        }.start()
    }

    private fun removeFiles(files: List<Item>) {
        // a bulk download still running would bring them back
        AttachmentDownloads.stop(this)
        Thread {
            val removed = filesOnDevice.remove(files)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, "Removed ${fileCount(removed)} from this device.", Toast.LENGTH_SHORT).show()
                (supportFragmentManager.findFragmentById(R.id.settings) as? SettingsFragment)?.showFilesOnDevice()
            }
        }.start()
    }

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
        val builder = AlertDialog.Builder(this).setTitle("Sign out?")
        when {
            unsynced == null -> builder.setMessage(
                "$forgets\n\nEdits waiting to sync are lost: sync first."
            )
            unsynced.isEmpty -> builder.setMessage(forgets)
            else -> builder.setMessage(
                "Not synced yet, and lost if you sign out now:\n" +
                        unsynced.describe().joinToString("\n") { "• $it" } +
                        "\n\nGo back to the library and sync first: pull down the list.\n\n$forgets"
            )
        }
        builder
            .setPositiveButton(if (unsynced?.isEmpty == true) "Sign out" else "Sign out anyway") { _, _ ->
                SignOut.signOut(this, zoteroDatabase)
            }
            .setNegativeButton("Cancel", null)
            .show()
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