package com.mickstarify.zooforzotero.ZoteroAPI

/** Thrown before any upload when the local file fails [AttachmentFileCheck]. */
class DamagedAttachmentException(message: String) : RuntimeException(message)

/**
 * Bookkeeping for uploading several edited attachments in one go. Uploads run one after
 * another; a refused or failed file doesn't stop the rest, and the user gets one summary.
 */
object AttachmentUploadBatch {

    /** @param failure null if [filename] was uploaded, otherwise why not */
    data class Outcome(val filename: String, val failure: String? = null)

    /** Why an upload failed, in words for the summary. */
    fun failureReason(e: Throwable): String = when (e) {
        is RemoteAttachmentChangedException ->
            "${e.message} Nothing was uploaded, so that version is safe; your annotated " +
                    "copy is still on this device."
        is DamagedAttachmentException ->
            "it wasn't uploaded because ${e.message}. Reopen it in your PDF viewer to check."
        is java.io.FileNotFoundException -> "the file is no longer on this device."
        is PreconditionFailedException ->
            "the server's copy is newer. Sync, after saving your annotated copy elsewhere."
        is RequestEntityTooLarge -> "there isn't enough storage quota for it."
        else -> e.toString()
    }

    /**
     * Whether to prompt on our own (not on request) about [changedKeys]: only if one of them
     * hasn't been offered yet, so that choosing "Later" isn't answered with the same question
     * every time the user comes back from the PDF viewer.
     */
    fun shouldOffer(changedKeys: Collection<String>, alreadyOffered: Set<String>): Boolean =
        changedKeys.any { it !in alreadyOffered }

    /** Title and message summarising a finished batch. */
    fun summary(outcomes: List<Outcome>): Pair<String, String> {
        val failed = outcomes.filter { it.failure != null }
        val uploaded = outcomes.size - failed.size
        if (failed.isEmpty()) {
            val what = if (uploaded == 1) "1 attachment" else "$uploaded attachments"
            return Pair("Upload finished", "Uploaded $what.")
        }
        val title = if (uploaded == 0) "Nothing uploaded" else "Some files not uploaded"
        val lines = failed.joinToString("\n\n") { "${it.filename}: ${it.failure}" }
        val head = if (outcomes.size == 1) "" else "Uploaded $uploaded of ${outcomes.size}.\n\n"
        return Pair(title, head + lines)
    }
}
