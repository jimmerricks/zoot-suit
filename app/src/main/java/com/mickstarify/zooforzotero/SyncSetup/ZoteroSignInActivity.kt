package com.mickstarify.zooforzotero.SyncSetup

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.SyncSetup.ui.components.ZoteroOAuthWebViewClient
import com.mickstarify.zooforzotero.SyncSetup.viewmodels.ZoteroAccountAuthViewModel
import com.mickstarify.zooforzotero.SyncSetup.viewmodels.ZoteroAccountAuthViewModel.Event
import com.mickstarify.zooforzotero.common.collectWhileStarted
import dagger.hilt.android.AndroidEntryPoint

/**
 * Sign-in on zotero.org (OAuth): asks Zotero for a request token, shows its login page,
 * and swaps the token for a key when the page redirects back. The key is stored and the
 * screen closes; [SyncSetupActivity] then opens the library.
 */
@AndroidEntryPoint
class ZoteroSignInActivity : AppCompatActivity() {
    private val viewModel: ZoteroAccountAuthViewModel by viewModels()
    private lateinit var webView: WebView
    private var errorDialog: AlertDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // white status bar icons over the toolbar, as on Settings
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zotero_sign_in)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = "Sign in to Zotero"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.zotero_sign_in_content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        webView = findViewById(R.id.zotero_sign_in_webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = ZoteroOAuthWebViewClient(
            onOAuthCallback = { token, verifier ->
                viewModel.handleEvent(Event.HandleOAuthCallback(token, verifier))
            },
            onError = { message -> showError(message) }
        )

        val loading = findViewById<View>(R.id.zotero_sign_in_loading)
        val failed = findViewById<View>(R.id.zotero_sign_in_failed)
        findViewById<View>(R.id.zotero_sign_in_retry).setOnClickListener {
            viewModel.handleEvent(Event.StartOAuthFlow)
        }

        // once per screen: the ViewModel keeps the page's address across rotation
        val state = viewModel.state.value
        if (state.authorizationUrl == null && !state.isLoading) {
            viewModel.handleEvent(Event.StartOAuthFlow)
        }

        collectWhileStarted(viewModel.state) { s ->
            loading.visibility = if (s.isLoading) View.VISIBLE else View.GONE
            val url = s.authorizationUrl
            webView.visibility = if (!s.isLoading && url != null) View.VISIBLE else View.GONE
            failed.visibility = if (!s.isLoading && url == null) View.VISIBLE else View.GONE
            if (url != null && webView.url == null) webView.loadUrl(url)
            s.error?.let { showError(it) }
        }
        collectWhileStarted(viewModel.effects) { effect ->
            if (effect is ZoteroAccountAuthViewModel.Effect.NavigateToLibrary) finish()
        }
    }

    private fun showError(message: String) {
        if (errorDialog?.isShowing == true) return
        errorDialog = AlertDialog.Builder(this)
            .setTitle("Sign-in failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setOnDismissListener { viewModel.handleEvent(Event.DismissError) }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        errorDialog?.dismiss()
        webView.destroy()
        super.onDestroy()
    }
}
