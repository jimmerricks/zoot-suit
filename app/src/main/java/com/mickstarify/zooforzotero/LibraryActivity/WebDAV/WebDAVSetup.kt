package com.mickstarify.zooforzotero.LibraryActivity.WebDAV

import android.app.ProgressDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.textfield.TextInputLayout
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.WebdavAuthMode
import com.mickstarify.zooforzotero.ZoteroAPI.Webdav
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

class WebDAVSetup : AppCompatActivity() {
    lateinit var preferenceManager: PreferenceManager
    lateinit var linearLayourAdvancedConfig: LinearLayout

    lateinit var textInputLayout_connectTimeout: TextInputLayout
    lateinit var textInputLayout_readTimeout: TextInputLayout
    lateinit var textInputLayout_writeTimeout: TextInputLayout

    lateinit var textInputLayout_host: TextInputLayout
    lateinit var textInputLayout_user: TextInputLayout
    lateinit var textInputLayout_password: TextInputLayout

    lateinit var textInputLayout_auth_mode: TextInputLayout

    lateinit var checkBox_verifySSL: CompoundButton
    lateinit var checkBox_formatAddress: CompoundButton

    override fun onCreate(savedInstanceState: Bundle?) {
        // as Settings, which opens it: white status bar icons over the toolbar's colour
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_dav_setup)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.webdav_config_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // the toolbar's AppBarLayout takes the status bar; keep the form above the nav bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.webdav_content)) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        preferenceManager = PreferenceManager(this)

        textInputLayout_connectTimeout = findViewById(R.id.textInputLayout_webdav_connect_timeout)
        textInputLayout_readTimeout = findViewById(R.id.textInputLayout_webdav_read_timeout)
        textInputLayout_writeTimeout = findViewById(R.id.textInputLayout_webdav_write_timeout)

        textInputLayout_host = findViewById(R.id.textInputLayout_webdav_host)
        textInputLayout_user = findViewById(R.id.textInputLayout_webdav_user)
        textInputLayout_password = findViewById(R.id.textInputLayout_webdav_password)

        checkBox_verifySSL = findViewById(R.id.checkBox_Verify_ssl)
        checkBox_formatAddress = findViewById(R.id.checkBox_add_zotero_host)
        // the whole row toggles its switch, as in Settings
        findViewById<View>(R.id.row_verify_ssl).setOnClickListener { checkBox_verifySSL.toggle() }
        findViewById<View>(R.id.row_add_zotero_host).setOnClickListener { checkBox_formatAddress.toggle() }

        textInputLayout_auth_mode = findViewById(R.id.TextInputLayout_webdav_auth_mode)

        val authModes = listOf(WebdavAuthMode.AUTOMATIC, WebdavAuthMode.BASIC, WebdavAuthMode.DIGEST)
        val authModeAdapter = ArrayAdapter(
            this, R.layout.webdav_auth_mode_list_item, authModes.map { it.name }
        )
        (textInputLayout_auth_mode.editText as AutoCompleteTextView).apply {
            setAdapter(authModeAdapter)
            // the saved mode; false: don't filter the list down to it
            setText(preferenceManager.getWebDAVAuthMode().name, false)

            // a tap in the list is an item click; onItemSelected only follows the keyboard
            setOnItemClickListener { _, _, position, _ ->
                val authMode = authModes[position]
                Log.d("Webdav", "Setting auth mode to ${authMode}")
                preferenceManager.setWebDAVAuthMode(authMode)
            }
        }

        loadConfig()
        settingsBefore = SettingsSnapshot()

        val submitButton = findViewById<Button>(R.id.button_webdav_connect)

        submitButton.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener

            val address = textInputLayout_host.editText?.text.toString()
            val username = textInputLayout_user.editText?.text.toString()
            val password = textInputLayout_password.editText?.text.toString()
            if (address == "") {
                destroyWebDAVAuthentication()
            } else {
                makeConnection(address, username, password)
            }
        }

        val advancedConfigButton: View = findViewById(R.id.button_webdav_advanced_config)
        linearLayourAdvancedConfig = findViewById(R.id.linearLayout_advanced_config)
        advancedConfigButton.setOnClickListener {
            toggleAdvancedConfig()
        }
    }

    // back to Settings, which opened this screen, not to the library (parentActivityName)
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * The WebDAV settings as this screen found them. The connection test reads the settings
     * being tried from the preferences, so they are saved first; if the test fails they are
     * put back, and a setup that worked before keeps working.
     */
    private inner class SettingsSnapshot {
        val enabled = preferenceManager.isWebDAVEnabled()
        val address = preferenceManager.getWebDAVAddress()
        val username = preferenceManager.getWebDAVUsername()
        val password = preferenceManager.getWebDAVPassword()
        val verifySSL = preferenceManager.getVerifySSLForWebDAV()
        val addZoteroToUrl = preferenceManager.getWebDAVAddZoteroToUrl()
        val authMode = preferenceManager.getWebDAVAuthMode()
        val connectTimeout = preferenceManager.getWebDAVConnectTimeout()
        val readTimeout = preferenceManager.getWebDAVReadTimeout()
        val writeTimeout = preferenceManager.getWebDAVWriteTimeout()

        fun restore() {
            preferenceManager.setWebDAVAuthentication(address, username, password)
            preferenceManager.setVerifySSLForWebDAV(verifySSL)
            preferenceManager.setWebDAVAddZoteroToUrl(addZoteroToUrl)
            preferenceManager.setWebDAVAuthMode(authMode)
            preferenceManager.setWebDAVConnectTimeout(connectTimeout)
            preferenceManager.setWebDAVReadTimeout(readTimeout)
            preferenceManager.setWebDAVWriteTimeout(writeTimeout)
            preferenceManager.setWebDAVEnabled(enabled)
        }
    }

    private lateinit var settingsBefore: SettingsSnapshot

    /** Saves the form for the connection test; false if a field is invalid (nothing saved). */
    private fun saveConfig(): Boolean {
        var host = textInputLayout_host.editText!!.text.toString()
        val username = textInputLayout_user.editText!!.text.toString()
        val password = textInputLayout_password.editText!!.text.toString()
        val verifySSL: Boolean = checkBox_verifySSL.isChecked
        val formatAddress = checkBox_formatAddress.isChecked
        if (formatAddress) {
            host = formatAddress(host)
            textInputLayout_host.editText!!.setText(host)
        }
        val connectTimeout: Long
        try {
            connectTimeout = textInputLayout_connectTimeout.editText!!.text.toString().toLong()
            if (connectTimeout < 0) {
                throw(NumberFormatException())
            }
        } catch (e: NumberFormatException) {
            textInputLayout_connectTimeout.error = "Enter a whole number of milliseconds, 0 or more."
            Toast.makeText(this, "Check the highlighted field.", Toast.LENGTH_SHORT).show()
            return false
        }

        val readTimeout: Long
        try {
            readTimeout = textInputLayout_readTimeout.editText!!.text.toString().toLong()
            if (readTimeout < 0L) {
                throw(NumberFormatException())
            }
        } catch (e: NumberFormatException) {
            textInputLayout_readTimeout.error = "Enter a whole number of milliseconds, 0 or more."
            Toast.makeText(this, "Check the highlighted field.", Toast.LENGTH_SHORT).show()
            return false
        }

        val writeTimeout: Long
        try {
            writeTimeout = textInputLayout_writeTimeout.editText!!.text.toString().toLong()
            if (writeTimeout < 0) {
                throw(NumberFormatException())
            }
        } catch (e: NumberFormatException) {
            textInputLayout_writeTimeout.error = "Enter a whole number of milliseconds, 0 or more."
            Toast.makeText(this, "Check the highlighted field.", Toast.LENGTH_SHORT).show()
            return false
        }

        preferenceManager.setWebDAVAuthentication(host, username = username, password = password)
        preferenceManager.setVerifySSLForWebDAV(verifySSL)
        preferenceManager.setWebDAVAddZoteroToUrl(formatAddress)
        preferenceManager.setWebDAVConnectTimeout(connectTimeout)
        preferenceManager.setWebDAVReadTimeout(readTimeout)
        preferenceManager.setWebDAVWriteTimeout(writeTimeout)
        // auth mode gets written at time of select, no need to write here.
        return true
    }

    private fun loadConfig() {
        textInputLayout_host.editText?.setText(preferenceManager.getWebDAVAddress())
        textInputLayout_user.editText?.setText(preferenceManager.getWebDAVUsername())
        textInputLayout_password.editText?.setText(preferenceManager.getWebDAVPassword())

        checkBox_verifySSL.isChecked = preferenceManager.getVerifySSLForWebDAV()
        checkBox_formatAddress.isChecked = preferenceManager.getWebDAVAddZoteroToUrl()

        textInputLayout_connectTimeout.editText!!.setText(
            preferenceManager.getWebDAVConnectTimeout().toString()
        )
        textInputLayout_readTimeout.editText!!.setText(
            preferenceManager.getWebDAVReadTimeout().toString()
        )
        textInputLayout_writeTimeout.editText!!.setText(
            preferenceManager.getWebDAVWriteTimeout().toString()
        )
    }

    private fun toggleAdvancedConfig() {
        val showing = linearLayourAdvancedConfig.visibility == View.VISIBLE

        val transition = AutoTransition()
        transition.setDuration(200)
        transition.addTarget(R.id.linearLayout_advanced_config)

        TransitionManager.beginDelayedTransition(linearLayourAdvancedConfig, transition)
        linearLayourAdvancedConfig.visibility = if (showing) View.GONE else View.VISIBLE
        // the chevron points down while closed, up while open
        findViewById<ImageView>(R.id.imageView_webdav_advanced_chevron).animate()
            .rotation(if (showing) 90f else -90f).setDuration(200).start()
    }

    fun formatAddress(address: String): String {
        var mAddress = address.trim()
        if (mAddress == "") {
            return ""
        }
        mAddress = if (!mAddress.uppercase().startsWith("HTTP")) {
            "https://" + mAddress
        } else {
            mAddress.trim()
        }
        mAddress = if (mAddress.endsWith("/zotero")) {
            mAddress
        } else {
            if (mAddress.endsWith("/")) { // so we don't get server.com//zotero
                mAddress + "zotero"
            } else {
                mAddress + "/zotero"
            }
        }
        return mAddress
    }

    fun makeConnection(address: String, username: String, password: String) {
        val webDav = Webdav(preferenceManager)
        startProgressDialog()
        Completable.fromAction {
            if (!webDav.testConnection()) {
                // testConnection's false is a 404
                throw Exception("There is no folder at $address. Create it on the server " +
                        "(Zotero desktop does this when you set up WebDAV), or check the address.")
            }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : CompletableObserver {
                override fun onSubscribe(d: Disposable) {
                }

                override fun onComplete() {
                    setWebDAVAuthentication(address, username, password)
                    hideProgressDialog()
                }

                override fun onError(e: Throwable) {
                    settingsBefore.restore()
                    notifyFailed("Check the address, username and password.\n\nDetails: ${e.message ?: e}")
                    hideProgressDialog()
                }

            })
    }

    var progressDialog: ProgressDialog? = null
    fun startProgressDialog() {
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this)
        }
        progressDialog?.setTitle("Connecting")
        progressDialog?.setMessage("Testing the connection to your WebDAV server…")
        progressDialog?.isIndeterminate = true
        progressDialog?.show()
    }

    fun hideProgressDialog() {
        // dismiss, not hide: a hidden dialog keeps its window, leaked when the screen closes
        progressDialog?.dismiss()
        progressDialog = null
    }

    fun notifyFailed(message: String = "") {
        val alertDialog = AlertDialog.Builder(this)
        alertDialog.setTitle("Couldn't connect to the WebDAV server")
        if (message == "") {
            alertDialog.setMessage(
                "Check the address, username and password."
            )
        } else {
            alertDialog.setMessage(message)
        }
        alertDialog.setPositiveButton("OK", null)
        alertDialog.show()
    }

    fun setWebDAVAuthentication(address: String, username: String, password: String) {
        preferenceManager.setWebDAVAuthentication(address, username, password)
        preferenceManager.setWebDAVEnabled(true)
        settingsBefore = SettingsSnapshot()
        Toast.makeText(this, "WebDAV server connected.", Toast.LENGTH_SHORT).show()
    }

    fun destroyWebDAVAuthentication() {
        preferenceManager.destroyWebDAVAuthentication()
        preferenceManager.setWebDAVEnabled(false)
        this.finish()
    }
}
