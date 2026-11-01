package com.mickstarify.zooforzotero.SyncSetup.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OAuthNavigationTest {
    @Test
    fun `the callback is caught`() {
        assertEquals(OAuthNavigation.CALLBACK, oauthNavigation("$OAUTH_CALLBACK?oauth_token=a&oauth_verifier=b"))
    }

    @Test
    fun `zotero org over https loads in the page`() {
        assertEquals(OAuthNavigation.LOAD, oauthNavigation("https://www.zotero.org/user/login"))
        assertEquals(OAuthNavigation.LOAD, oauthNavigation("https://zotero.org/"))
        assertEquals(OAuthNavigation.LOAD, oauthNavigation("HTTPS://WWW.ZOTERO.ORG/oauth/authorize"))
    }

    @Test
    fun `other sites and plain http go to the browser`() {
        assertEquals(OAuthNavigation.EXTERNAL, oauthNavigation("https://example.com/"))
        assertEquals(OAuthNavigation.EXTERNAL, oauthNavigation("https://zotero.org.example.com/"))
        assertEquals(OAuthNavigation.EXTERNAL, oauthNavigation("https://evilzotero.org/"))
        assertEquals(OAuthNavigation.EXTERNAL, oauthNavigation("http://www.zotero.org/user/login"))
    }

    @Test
    fun `other schemes are blocked`() {
        assertEquals(OAuthNavigation.BLOCK, oauthNavigation("javascript:alert(1)"))
        assertEquals(OAuthNavigation.BLOCK, oauthNavigation("file:///data/data/x"))
        assertEquals(OAuthNavigation.BLOCK, oauthNavigation("intent://x#Intent;end"))
        assertEquals(OAuthNavigation.BLOCK, oauthNavigation("not a url"))
    }
}
