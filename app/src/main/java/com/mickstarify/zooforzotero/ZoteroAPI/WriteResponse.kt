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
    /**
     * Why an object wasn't written: [code] as Zotero gives it per object (412: it exists
     * at another version, 409: e.g. its parent item doesn't exist), null if the body
     * couldn't be read at all.
     */
    data class Failure(val code: Int?, val message: String) {
        override fun toString() = "${code ?: "?"}: $message"
    }

    /** The first failed object, or null if nothing failed. */
    fun firstFailure(body: String): Failure? {
        val root = try {
            Gson().fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return Failure(null, "unreadable server response")
        } ?: return Failure(null, "empty server response")
        val failed = root.getAsJsonObject("failed") ?: return null
        val first = failed.entrySet().firstOrNull() ?: return null
        val detail = first.value.asJsonObject
        val code = detail.get("code")?.takeUnless { it.isJsonNull }?.asInt
        val message = detail.get("message")?.takeUnless { it.isJsonNull }?.asString ?: "no message"
        return Failure(code, message)
    }
}
