package com.mickstarify.zooforzotero

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * The "theme" setting: auto follows the system's dark mode (Android 10 and later; earlier
 * versions have none, so auto is light there), light and dark override it. Applied at
 * process start and again when the setting changes, which recreates the open screens.
 */
object AppTheme {
    const val KEY = "theme"

    fun apply(context: Context) {
        val value = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "auto")
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
