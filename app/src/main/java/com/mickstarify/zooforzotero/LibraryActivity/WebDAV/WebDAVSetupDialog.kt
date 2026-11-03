package com.mickstarify.zooforzotero.LibraryActivity.WebDAV

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.textfield.TextInputLayout
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.WebdavAuthMode
import com.mickstarify.zooforzotero.ZoteroAPI.Webdav
import com.mickstarify.zooforzotero.common.DialogActions
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * "WebDAV server", opened from Settings: a dialog built like the library's. The address and
 * login, and under Advanced the address format, certificate check, authentication and
 * timeouts; then "Save and connect", which tests the connection and, if it works, turns
 * WebDAV on and closes; "Remove server" while one is set up; and Cancel, which changes
 * nothing. A failed test puts the settings back as they were. [onChanged]: the setup was
 * saved or removed (Settings shows the address and the switch again).
 */
class WebDAVSetupDialog(private val context: Context, private val onChanged: () -> Unit) {
    private val preferenceManager = PreferenceManager(context)

    private lateinit var dialog: AlertDialog
    private lateinit var host: TextInputLayout
    private lateinit var user: TextInputLayout
    private lateinit var password: TextInputLayout
    private lateinit var connectTimeout: TextInputLayout
    private lateinit var readTimeout: TextInputLayout
    private lateinit var writeTimeout: TextInputLayout
    private lateinit var verifySSL: CompoundButton
    private lateinit var formatAddress: CompoundButton
    private lateinit var connect: Button
    private var authMode = preferenceManager.getWebDAVAuthMode()
    private var test: Disposable? = null

    fun show() {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_webdav_setup, null)
        host = view.findViewById(R.id.textInputLayout_webdav_host)
        user = view.findViewById(R.id.textInputLayout_webdav_user)
        password = view.findViewById(R.id.textInputLayout_webdav_password)
        connectTimeout = view.findViewById(R.id.textInputLayout_webdav_connect_timeout)
        readTimeout = view.findViewById(R.id.textInputLayout_webdav_read_timeout)
        writeTimeout = view.findViewById(R.id.textInputLayout_webdav_write_timeout)
        verifySSL = view.findViewById(R.id.checkBox_Verify_ssl)
        formatAddress = view.findViewById(R.id.checkBox_add_zotero_host)
        connect = view.findViewById(R.id.button_webdav_connect)
        val remove = view.findViewById<Button>(R.id.button_webdav_remove)

        // an http:// address: the password (sent as is with Basic) and files go unencrypted
        val hostHelp = host.helperText
        host.editText!!.doAfterTextChanged { text ->
            host.helperText = if (isPlainHttp(text.toString())) context.getString(R.string.webdav_http_warning) else hostHelp
            host.error = null
        }
        listOf(connectTimeout, readTimeout, writeTimeout).forEach { field ->
            field.editText!!.doAfterTextChanged { field.error = null }
        }

        // chosen here, saved with the rest
        val authModes = listOf(WebdavAuthMode.AUTOMATIC, WebdavAuthMode.BASIC, WebdavAuthMode.DIGEST)
        (view.findViewById<TextInputLayout>(R.id.TextInputLayout_webdav_auth_mode).editText as AutoCompleteTextView).apply {
            setAdapter(ArrayAdapter(builder.context, R.layout.webdav_auth_mode_list_item, authModes.map { it.name }))
            // false: don't filter the list down to the saved mode
            setText(authMode.name, false)
            // a tap in the list is an item click; onItemSelected only follows the keyboard
            setOnItemClickListener { _, _, position, _ -> authMode = authModes[position] }
        }

        val advanced = view.findViewById<LinearLayout>(R.id.linearLayout_advanced_config)
        view.findViewById<View>(R.id.button_webdav_advanced_config).setOnClickListener {
            val showing = advanced.visibility == View.VISIBLE
            TransitionManager.beginDelayedTransition(advanced, AutoTransition().setDuration(200).addTarget(advanced))
            advanced.visibility = if (showing) View.GONE else View.VISIBLE
            // the chevron points down while closed, up while open
            view.findViewById<ImageView>(R.id.imageView_webdav_advanced_chevron).animate()
                .rotation(if (showing) 90f else -90f).setDuration(200).start()
        }

        loadConfig()

        dialog = builder
            .setTitle(R.string.webdav_config_title)
            .setView(view)
            .setOnDismissListener { test?.dispose() }
            .create()
        DialogActions.leave(view, dialog, "Cancel")
        connect.setOnClickListener { saveAndConnect() }
        remove.visibility = if (preferenceManager.isWebDAVConfigured()) View.VISIBLE else View.GONE
        remove.setOnClickListener {
            preferenceManager.destroyWebDAVAuthentication()
            preferenceManager.setWebDAVEnabled(false)
            dialog.dismiss()
            onChanged()
        }
        // no keyboard until a field is tapped; with it, the form shrinks above it
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
    }

    private fun loadConfig() {
        host.editText!!.setText(preferenceManager.getWebDAVAddress())
        user.editText!!.setText(preferenceManager.getWebDAVUsername())
        password.editText!!.setText(preferenceManager.getWebDAVPassword())
        verifySSL.isChecked = preferenceManager.getVerifySSLForWebDAV()
        formatAddress.isChecked = preferenceManager.getWebDAVAddZoteroToUrl()
        connectTimeout.editText!!.setText(preferenceManager.getWebDAVConnectTimeout().toString())
        readTimeout.editText!!.setText(preferenceManager.getWebDAVReadTimeout().toString())
        writeTimeout.editText!!.setText(preferenceManager.getWebDAVWriteTimeout().toString())
    }

    /** A timeout field's milliseconds, or null with the mistake shown under it. */
    private fun millis(field: TextInputLayout): Long? =
        field.editText!!.text.toString().toLongOrNull()?.takeIf { it >= 0 } ?: run {
            field.error = "Enter a whole number of milliseconds, 0 or more."
            null
        }

    /**
     * The WebDAV settings as the dialog found them. The connection test reads the settings
     * being tried from the preferences, so they are saved first; if the test fails they
     * are put back, and a setup that worked before keeps working.
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

    private fun saveAndConnect() {
        val connectMs = millis(connectTimeout)
        val readMs = millis(readTimeout)
        val writeMs = millis(writeTimeout)
        if (connectMs == null || readMs == null || writeMs == null) return
        var address = host.editText!!.text.toString()
        if (formatAddress.isChecked) {
            address = formatAddress(address)
            host.editText!!.setText(address)
        }
        if (address.isBlank()) {
            host.error = "Enter the server's address, or remove the server."
            return
        }
        val username = user.editText!!.text.toString()
        val password = password.editText!!.text.toString()

        val before = SettingsSnapshot()
        preferenceManager.setWebDAVAuthentication(address, username = username, password = password)
        preferenceManager.setVerifySSLForWebDAV(verifySSL.isChecked)
        preferenceManager.setWebDAVAddZoteroToUrl(formatAddress.isChecked)
        preferenceManager.setWebDAVAuthMode(authMode)
        preferenceManager.setWebDAVConnectTimeout(connectMs)
        preferenceManager.setWebDAVReadTimeout(readMs)
        preferenceManager.setWebDAVWriteTimeout(writeMs)
        Log.d("Webdav", "testing $address with auth mode $authMode")

        connect.isEnabled = false
        connect.setText(R.string.webdav_connecting)
        test = Completable.fromAction {
            if (!Webdav(preferenceManager).testConnection()) {
                // testConnection's false is a 404
                throw Exception("There is no folder at $address. Create it on the server " +
                        "(Zotero desktop does this when you set up WebDAV), or check the address.")
            }
        }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                preferenceManager.setWebDAVEnabled(true)
                android.widget.Toast.makeText(context, "WebDAV server connected.", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onChanged()
            }, { e ->
                before.restore()
                connect.isEnabled = true
                connect.setText(R.string.save_connect)
                AlertDialog.Builder(context)
                    .setTitle("Couldn't connect to the WebDAV server")
                    .setMessage("Check the address, username and password.\n\nDetails: ${e.message ?: e}")
                    .setPositiveButton("OK", null)
                    .show()
            })
    }

    private fun isPlainHttp(address: String) = address.trim().startsWith("http://", ignoreCase = true)

    /** [address] with https:// if it has no scheme, and /zotero at the end if missing. */
    private fun formatAddress(address: String): String {
        var a = address.trim()
        if (a.isEmpty()) return ""
        if (!a.uppercase().startsWith("HTTP")) a = "https://$a"
        if (!a.endsWith("/zotero")) a = if (a.endsWith("/")) a + "zotero" else "$a/zotero"
        return a
    }
}
