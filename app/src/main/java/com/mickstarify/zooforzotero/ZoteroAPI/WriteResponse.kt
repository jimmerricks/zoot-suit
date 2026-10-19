package com.mickstarify.zooforzotero.ZoteroAPI

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Reads the body of a Zotero multi-object write (POST <prefix>/items etc.).
 *
 * The API answers 200 even when objects were rejected; per-object results are in
 * {"successful": {...}, "unchanged": {...}, "failed": {"<index>": {"key", "code",
 * "message"}}}. So a 200 alone doesn't mean the write happened.
 */
object WriteResponse {
    /** A description of the first failed object, or null if nothing failed. */
    fun firstFailure(body: String): String? {
        val root = try {
            Gson().fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return "unreadable server response"
        } ?: return "empty server response"
        val failed = root.getAsJsonObject("failed") ?: return null
        val first = failed.entrySet().firstOrNull() ?: return null
        val detail = first.value.asJsonObject
        val code = detail.get("code")?.asString ?: "?"
        val message = detail.get("message")?.asString ?: "no message"
        return "$code: $message"
    }
}
