package com.mickstarify.zooforzotero.SyncSetup.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.mickstarify.zooforzotero.ui.theme.ZoteroTheme

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZoteroOAuthWebView(
    url: String,
    onOAuthCallback: (String, String) -> Unit,
    onPageFinished: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }
    }

    DisposableEffect(url) {
        onDispose {
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.webViewClient = object : WebViewClient() {
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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    onPageFinished()
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.e(TAG, "WebView error $errorCode: $description for URL: $failingUrl")
                    onError("Couldn't reach Zotero: $description")
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                }
            }

            // Only load URL if it's different from current URL to avoid reloading
            if (view.url != url) {
                view.loadUrl(url)
            }
        }
    )
}

@Preview
@Composable
private fun ZoteroOAuthWebViewPreview() {
    ZoteroTheme {
        ZoteroOAuthWebView(
            url = "https://www.zotero.org/oauth/authorize",
            onOAuthCallback = { _, _ -> },
            onPageFinished = { },
            onError = { }
        )
    }
}