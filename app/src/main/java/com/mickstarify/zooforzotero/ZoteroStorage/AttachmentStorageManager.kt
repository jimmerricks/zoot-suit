package com.mickstarify.zooforzotero.ZoteroStorage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

const val STORAGE_ACCESS_REQUEST = 1  // The request code

private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

/* This classes handles the data storage for all attachments. */

/**
 * [name] (an attachment's filename, as Zotero has it) as a name inside one folder. Zotero
 * refuses stored-file names with a directory path, so this changes nothing in practice;
 * but the name comes from the server and goes into a path, and "../" in it would reach
 * outside the attachment folder (and createTempFile deletes what's there).
 */
fun safeFileName(name: String?): String {
    val base = name?.substringAfterLast('/')?.replace("\u0000", "")
    return if (base.isNullOrBlank() || base == "." || base == "..") "unknown.pdf" else base
}


@Singleton
class AttachmentStorageManager @Inject constructor(
    val context: Context,
    val preferenceManager: PreferenceManager
) {

    enum class StorageMode {
        CUSTOM,
        EXTERNAL_CACHE,
        NONE_SET
    }

    val storageMode: StorageMode
        get() = when (preferenceManager.getStorageMode()) {
            "CUSTOM" -> StorageMode.CUSTOM
            "EXTERNAL_CACHE" -> StorageMode.EXTERNAL_CACHE
            else -> StorageMode.EXTERNAL_CACHE
        }


    fun validateAccess() {
        if (storageMode == StorageMode.CUSTOM) {
            if (testStorage() == false) {
                throw (IOException("Cannot Read Attachment Directory"))
            }
        }
    }

    fun getFilenameForItem(item: Item): String = safeFileName(item.data["filename"])

    fun checkIfAttachmentExists(item: Item, checkMd5: Boolean = true): Boolean {
        if (storageMode == StorageMode.EXTERNAL_CACHE) {
            val filename = getFilenameForItem(item)
            val outputDir = File(context.externalCacheDir, item.itemKey.uppercase())
            if (!outputDir.exists() || outputDir.isDirectory == false) {
                return false
            }
            val file = File(outputDir, filename)
            if (file.length() == 0L) {
                file.delete()
                return false
            }
            if (checkMd5) {
                return file.exists() && calculateMd5(file.inputStream()) == item.data["md5"]
            } else {
                return file.exists()
            }

        } else if (storageMode == StorageMode.CUSTOM) {
            val file = findCustomAttachment(item) ?: return false
            if (file.length() == 0L) {
                file.delete()
                return false
            }
            val exists = file.exists()
            if (exists && checkMd5) {
                return calculateMd5(context.contentResolver.openInputStream(file.uri)!!) == item.data["md5"]
            } else {
                return exists
            }
        }

        Log.e("zotero", "checkIfAttachmentExists called with unconfigured storage mode")
        return false
    }

    fun calculateMd5(attachment: Item): String {
        val attachmentInputStream = this.getItemInputStream(attachment)
        return calculateMd5(attachmentInputStream)
    }

    /** Lower-case hex md5 of the stream, which it closes. Reads it all: not on the main thread. */
    fun calculateMd5(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream.use { stream ->
            // 64 KB: through a Custom Path folder each read is a binder call
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun getItemOutputStream(item: Item): OutputStream {
        val filename = getFilenameForItem(item)
        val mimeType = item.data["contentType"] ?: "application/pdf"
        if (storageMode == StorageMode.EXTERNAL_CACHE) {
            val file = getAttachmentFile(item)
            file.parentFile?.mkdirs()
            return file.outputStream()
        } else if (storageMode == StorageMode.CUSTOM) {
            val documentTree = DocumentFile.fromTreeUri(context, getCustomStorageTreeURI())
            var directory = documentTree?.findFile(item.itemKey.uppercase())
            if (directory == null || directory.isDirectory == false) {
                directory = documentTree?.createDirectory(item.itemKey.uppercase())
            }
            var itemFile = directory!!.findFile(filename)
            if (itemFile == null || !itemFile.exists()) {
                itemFile = directory.createFile(mimeType, filename)
            }
            // "wt", not the default "w": on several providers "w" doesn't truncate, so a
            // smaller file written over a larger one kept the old tail (and failed its md5)
            return context.contentResolver.openOutputStream(itemFile!!.uri, "wt")!!
        }
        throw Exception("not implemented")
    }

    fun getItemInputStream(item: Item): InputStream {
        val filename = getFilenameForItem(item)
        val mimeType = item.data["contentType"] ?: "application/pdf"
        if (storageMode == StorageMode.EXTERNAL_CACHE) {
            val file = getAttachmentFile(item)
            return file.inputStream()
        } else if (storageMode == StorageMode.CUSTOM) {
            return context.contentResolver.openInputStream(getAttachmentUri(item))!!
        }
        throw Exception("not implemented")
    }

    fun getAttachmentUri(item: Item): Uri {
        if (storageMode == StorageMode.EXTERNAL_CACHE) {
            val file = getAttachmentFile(item)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider.app",
                    file
                )
            } else {
                return Uri.fromFile(file)
            }

        } else if (storageMode == StorageMode.CUSTOM) {
            return findCustomAttachment(item)?.uri ?: throw FileNotFoundException()
        }
        throw Exception("not implemented")
    }

    /**
     * [item]'s file in the Custom Path folder, or null if it isn't there.
     *
     * DocumentFile.findFile lists the whole folder through the provider on every call;
     * the attachment folder holds one subfolder per downloaded attachment and this runs
     * once per attachment (edit checks, badges, sizes), so it grew quadratically. On
     * the device's own storage (ExternalStorageProvider) a document id is the file's
     * path, "primary:Zotero/KEY/name.pdf", so the file's URI can be built and checked
     * with a single query. Other providers have opaque ids and keep the listing.
     */
    private fun findCustomAttachment(item: Item): DocumentFile? {
        val tree = getCustomStorageTreeURI()
        val key = item.itemKey.uppercase()
        val filename = getFilenameForItem(item)
        if (tree.authority == EXTERNAL_STORAGE_PROVIDER) {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "$treeId/$key/$filename")
            return DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }
        }
        val directory = DocumentFile.fromTreeUri(context, tree)?.findFile(key)
            ?.takeIf { it.isDirectory } ?: return null
        return directory.findFile(filename)?.takeIf { it.exists() }
    }

    fun askUserForPath(activity: Activity) {
        /* Attempts to get access to a directory to store zotero storage. */
        val intent = Intent()
        intent.action = Intent.ACTION_OPEN_DOCUMENT_TREE
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivityForResult(
            intent,
            STORAGE_ACCESS_REQUEST
        )
    }

    fun openAttachment(attachment: Item): Intent {
        var intent = Intent(Intent.ACTION_VIEW)
        var attachmentUri = getAttachmentUri(attachment)
        Log.d("zotero", "opening PDF with Uri $attachmentUri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = Intent(Intent.ACTION_VIEW)
            intent.data = attachmentUri
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } else {
            intent.setDataAndType(attachmentUri, attachment.data["contentType"])
            intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
            intent = Intent.createChooser(intent, "Open with")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        }
        return intent
    }

    fun openAttachment(uri: Uri, contentType: String = ""): Intent {
        var intent = Intent(Intent.ACTION_VIEW)
        Log.d("zotero", "opening PDF with Uri $uri")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } else {
            intent.setDataAndType(uri, contentType)
            intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
            intent = Intent.createChooser(intent, "Open with")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        }
        return intent
    }


    fun testStorage(): Boolean {
        if (storageMode == StorageMode.EXTERNAL_CACHE) {
            return true //nothing to test.
        }

        val location = preferenceManager.getCustomAttachmentStorageLocation()
        if (location == "") {
            return false
        }
        val rootDocFile = DocumentFile.fromTreeUri(context, Uri.parse(location))
        Log.d(
            "zotero",
            "testing dir canWrite=${rootDocFile?.canWrite()} canRead=${rootDocFile?.canRead()}"
        )
        return rootDocFile?.canWrite() == true
    }

    fun createTempFile(filename: String): File {
        val zipFile = File(context.cacheDir, safeFileName(filename))
        if (zipFile.exists()) {
            zipFile.delete()
        }
        return zipFile
    }

    fun setStorage(location: String?) {
        if (location == null) {
            Log.e("zotero", "error got null for location in setStorage()")
            return
        }

        val uri = Uri.parse(location)

        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        preferenceManager.setCustomAttachmentStorage(location)
        testStorage()
    }

    /** Keys (upper case) of attachments with a folder in storage, listed once. */
    fun listDownloadedItemKeys(): Set<String> {
        return when (storageMode) {
            StorageMode.EXTERNAL_CACHE ->
                context.externalCacheDir?.listFiles()?.filter { it.isDirectory }
                    ?.map { it.name.uppercase() }?.toSet() ?: emptySet()
            StorageMode.CUSTOM ->
                DocumentFile.fromTreeUri(context, getCustomStorageTreeURI())?.listFiles()
                    ?.filter { it.isDirectory }?.mapNotNull { it.name?.uppercase() }?.toSet()
                    ?: emptySet()
            else -> emptySet()
        }
    }

    fun getCustomStorageTreeURI(): Uri {
        return Uri.parse(preferenceManager.getCustomAttachmentStorageLocation())
    }

    fun getMtime(attachment: Item): Long {
        when (storageMode) {
            StorageMode.EXTERNAL_CACHE -> {
                val file = getAttachmentFile(attachment)
                return file.lastModified()
            }

            StorageMode.CUSTOM -> {
                val docFile = DocumentFile.fromSingleUri(context, getAttachmentUri(attachment))
                return docFile?.lastModified() ?: throw FileNotFoundException()
            }

            else -> throw Exception("Invalid storage mode")
        }

    }

    /**
     * Where [attachment]'s file is (or would be) in the app's cache folder. Creates nothing:
     * it used to create the folder, and every attachment whose size or state was looked up
     * left an empty one, which then counted as downloaded (listDownloadedItemKeys).
     */
    private fun getAttachmentFile(attachment: Item): File =
        File(File(context.externalCacheDir, attachment.itemKey.uppercase()), getFilenameForItem(attachment))

    fun getFileSize(attachment: Item): Result<Long> {
        return try {
            val size = if (storageMode == StorageMode.EXTERNAL_CACHE) {
                getAttachmentFile(attachment).length()
            } else if (storageMode == StorageMode.CUSTOM) {
                val uri = getAttachmentUri(attachment)
                getFileSize(uri)
            } else {
                throw Exception("Invalid storage mode")
            }
            Result.success(size)
        } catch (e: FileNotFoundException) {
            Log.d("AttachmentStorageManager", "File not found for attachment ${attachment.itemKey}: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("AttachmentStorageManager", "Error getting file size for attachment ${attachment.itemKey}: ${e.message}")
            Result.failure(e)
        }
    }

    fun getFileSize(attachmentUri: Uri): Long {
        val docFile = DocumentFile.fromSingleUri(context, attachmentUri)
        return docFile?.length() ?: throw FileNotFoundException()
    }

    /** The attachment's file as a request body that streams it, [length] bytes. */
    fun uploadBody(attachment: Item, length: Long, type: MediaType): RequestBody =
        object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                getItemInputStream(attachment).source().use { sink.writeAll(it) }
            }
        }

    fun writeAttachmentFromFile(file: File, attachment: Item): Uri {
        // closing the sink closes the output stream too
        file.inputStream().source().use { input ->
            getItemOutputStream(attachment).sink().buffer().use { it.writeAll(input) }
        }
        return getAttachmentUri(attachment)
    }

    fun deleteAttachment(attachment: Item) {
        when (storageMode) {
            StorageMode.EXTERNAL_CACHE -> {
                try {
                    getAttachmentFile(attachment).delete()
                } catch (e: Exception) {
                    Log.d("zotero", "cannot delete file. Not found.")
                }
            }

            StorageMode.CUSTOM -> {
                try {
                    val docFile = DocumentFile.fromSingleUri(context, getAttachmentUri(attachment))
                    docFile?.delete()
                } catch (e: java.io.FileNotFoundException) {
                    Log.d("zotero", "cannot delete file. Not found.")
                }
            }

            else -> throw Exception("not implemented")
        }
    }

    fun openLinkedAttachment(item: Item): Intent? {
        /* This method searches for the linked attachment in the users storage directory.
        * This may fail and return nothing. */
        if (item.data["linkMode"] != "linked_file") {
            throw Exception("error attempting to open linked attachment on something this isn't elligible.")
        }

        // will work as followed:
        // /home/michael/Downloads/Alice in Wonderland.pdf -> [home, michael, Downloads. Alice in Wonderland.pdf]
        // for each path in list, check if folder exists in root dir
        // if it does, pursue this path.
        // if it doesn't, return to the root path, and repeat for the next item in the list.


        var itemPath = item.data["path"] ?: ""
        // do some transformations for windows style paths
        // e.g C:\\Users\\michael\\file.txt -> C:/Users/michael/file.txt
        itemPath = itemPath.replace("\\", File.separator)
        //we get rid of zotero reserved expression on storaged path "attachemnts:"
        itemPath = itemPath.replace(Regex("^attachments:"), "")
        val directories = itemPath.split("/")
        if (storageMode == StorageMode.CUSTOM) {
            for (index in directories.indices) {
                val location = preferenceManager.getCustomAttachmentStorageLocation()
                var documentFile = DocumentFile.fromTreeUri(context, Uri.parse(location))
                var i = index
                while (i < directories.size) {
                    val id: String =
                        DocumentsContract.getDocumentId(documentFile?.uri) + "/" + directories[i]
                    documentFile = DocumentFile.fromSingleUri(
                        context,
                        DocumentsContract.buildDocumentUriUsingTree(documentFile?.uri, id)
                    )
                    Log.d("zotero", "checking ${directories[i]}")
                    if (documentFile?.exists() ?: false) {
                        i++
                    } else {
                        break
                    }
                }
                if (documentFile?.isFile ?: false) {
                    Log.d("zotero", "found file ${documentFile?.name}")
                    return openAttachment(documentFile!!.uri, item.data["contentType"] ?: "")
                }
            }
        } else if (storageMode == StorageMode.EXTERNAL_CACHE) {
            // logic using File api is much simpler
            for (i in directories.indices) {
                val path = directories.slice(i..directories.size - 1).joinToString("/")
                    .trimStart('/')
                if (path.isEmpty()) continue
                Log.d("zotero", "checking path $path")
                var document = File(context.externalCacheDir, path)
                if (document.exists()) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider.app",
                            document
                        )
                    } else {
                        Uri.fromFile(document)
                    }
                    return openAttachment(uri, item.data["contentType"] ?: "")
                }
            }


        } else {
            throw NotImplementedError()
        }

        return null
    }

}