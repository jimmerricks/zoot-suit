package com.mickstarify.zooforzotero.SyncSetup.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient

/** Where Zotero sends the browser after sign-in; caught here, never opened. */
const val OAUTH_CALLBACK = "zootsuit://oauth_callback"

private const val TAG = "ZoteroOAuthWebView"

/** What the sign-in page does with a link: the page only ever shows zotero.org over https. */
internal enum class OAuthNavigation { CALLBACK, LOAD, EXTERNAL, BLOCK }

internal fun oauthNavigation(url: String): OAuthNavigation {
    if (url.startsWith(OAUTH_CALLBACK)) return OAuthNavigation.CALLBACK
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return OAuthNavigation.BLOCK
    val host = uri.host?.lowercase() ?: return OAuthNavigation.BLOCK
    return when (uri.scheme?.lowercase()) {
        "https" -> if (host == "zotero.org" || host.endsWith(".zotero.org")) {
            OAuthNavigation.LOAD
        } else {
            OAuthNavigation.EXTERNAL
        }
        // the browser shows where it goes; here the user couldn't tell
        "http" -> OAuthNavigation.EXTERNAL
        else -> OAuthNavigation.BLOCK
    }
}

/**
 * The sign-in page's WebViewClient: keeps it on zotero.org ([oauthNavigation]) and hands
 * over the token and verifier when Zotero redirects to [OAUTH_CALLBACK].
 */
class ZoteroOAuthWebViewClient(
    private val onOAuthCallback: (token: String, verifier: String) -> Unit,
    private val onError: (String) -> Unit
) : WebViewClient() {

    @Deprecated("the String variant is the one API 24 calls")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val urlString = url ?: return true
        when (oauthNavigation(urlString)) {
            OAuthNavigation.CALLBACK -> {
                val uri = Uri.parse(urlString)
                val oauthToken = uri.getQueryParameter("oauth_token")
                val oauthVerifier = uri.getQueryParameter("oauth_verifier")
                if (oauthToken != null && oauthVerifier != null) {
                    onOAuthCallback(oauthToken, oauthVerifier)
                } else {
                    onError("Zotero's sign-in page didn't return a login. Try again.")
                }
            }
            // zotero.org over https: the sign-in page and its own links
            OAuthNavigation.LOAD -> return false
            // anywhere else opens in the browser, never inside the sign-in page
            OAuthNavigation.EXTERNAL -> runCatching {
                view?.context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlString)))
            }.onFailure { Log.e(TAG, "no app opens $urlString: $it") }
            OAuthNavigation.BLOCK -> Log.w(TAG, "blocked $urlString")
        }
        return true
    }

    @Deprecated("called for the page itself only, which is what matters here")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        Log.e(TAG, "WebView error $errorCode: $description for URL: $failingUrl")
        onError("Couldn't reach Zotero: $description")
    }
}
