package com.mickstarify.zooforzotero

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/** Settings → About: the version, and where to report problems, read the source and licence. */
object About {
    const val REPO = "https://github.com/jimmerricks/zoot-suit"
    const val LICENCE = "$REPO/blob/master/LICENSE"

    /** "0.4.2 (10)"; debug builds say so through their versionName suffix. */
    val version: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** What a problem report needs to know about this install. */
    fun deviceInfo(): String =
        "Zoot Suit $version\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}"

    /** A new GitHub issue, its body ending in [deviceInfo]. */
    fun reportUrl(): String =
        "$REPO/issues/new?body=" + Uri.encode("\n\n---\n${deviceInfo()}")

    fun copyVersion(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Zoot Suit version", deviceInfo()))
        // Android 13+ shows its own confirmation
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Version copied", Toast.LENGTH_SHORT).show()
        }
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser to open $url", Toast.LENGTH_LONG).show()
        }
    }
}
