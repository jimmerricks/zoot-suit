package com.mickstarify.zooforzotero

import android.content.Intent
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
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.mickstarify.zooforzotero.LibraryActivity.WebDAV.WebDAVSetup
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorage
import com.mickstarify.zooforzotero.SyncSetup.domain.SignOut
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
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
                myStorageManager.setStorage(data?.dataString)
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