package com.mickstarify.zooforzotero.ZoteroAPI

import android.util.Log
import okhttp3.logging.HttpLoggingInterceptor

private val KEY_IN_PATH = Regex("(/keys/)[^/?\\s]+")

/** [line] with an API key in a /keys/{key} URL replaced by "…". */
internal fun redactKeys(line: String): String = line.replace(KEY_IN_PATH, "$1…")

/**
 * The request log of debug builds: method, URL, status and timing, never headers or
 * bodies (the key and the /keys response, which holds it), and a key in a URL hidden.
 */
fun debugHttpLogging(): HttpLoggingInterceptor =
    HttpLoggingInterceptor { Log.d("OkHttp", redactKeys(it)) }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
    }
