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
    data class Failure(
        val code: Int?,
        val message: String,
        /** The key of a collection the object was put in that Zotero doesn't have. */
        val missingCollection: String? = null,
    ) {
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
        return failure(first.value.asJsonObject)
    }

    /**
     * Each object's outcome in a write of [count] objects, by its index in the request:
     * null where it was written (successful or unchanged), else why not. An index the body
     * doesn't mention, or a body that can't be read, counts as failed: unknown is not done.
     */
    fun outcomes(body: String, count: Int): List<Failure?> {
        val root = try {
            Gson().fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            null
        } ?: return List(count) { Failure(null, "unreadable server response") }
        val done = HashSet<Int>()
        for (name in listOf("successful", "success", "unchanged")) {
            root.getAsJsonObject(name)?.keySet()?.mapNotNullTo(done) { it.toIntOrNull() }
        }
        val failed = root.getAsJsonObject("failed")
        return List(count) { index ->
            val detail = failed?.get(index.toString())?.takeIf { it.isJsonObject }?.asJsonObject
            when {
                detail != null -> failure(detail)
                index in done -> null
                else -> Failure(null, "not in the server's answer")
            }
        }
    }

    private fun failure(detail: JsonObject): Failure {
        val code = detail.get("code")?.takeUnless { it.isJsonNull }?.asInt
        val message = detail.get("message")?.takeUnless { it.isJsonNull }?.asString ?: "no message"
        val data = detail.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val collection = data?.get("collection")?.takeUnless { it.isJsonNull }?.asString
            ?: MISSING_COLLECTION.find(message)?.groupValues?.get(1)
        return Failure(code, message, missingCollection = collection)
    }

    // Zotero's message for an unknown collection, if "data" lacks it: "Collection <key> not
    // found" (the API, 2026-10-30), or "Collection <libraryID>/<key> doesn't exist" (dataserver)
    private val MISSING_COLLECTION = Regex("""Collection (?:\d+/)?([23456789A-Z]{8}) (?:not found|doesn't exist)""")
}
