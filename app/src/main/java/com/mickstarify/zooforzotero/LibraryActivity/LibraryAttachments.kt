package com.mickstarify.zooforzotero.LibraryActivity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploadBatch
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploader
import com.mickstarify.zooforzotero.ZoteroAPI.DownloadProgress
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroNotFoundException
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentOpenCheck
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentCounts
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloadQueue
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloads
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentKind
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.FileNotFoundException
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * The library screen's attachment actions: opening a file (fetching Zotero's newer
 * version first when online), downloading, uploading edited files one by one or in a
 * batch, and deleting a copy from the device. Acts on the open library ([library]).
 *
 * @param api the API client, built on first use (see LibraryComponents)
 * @param onDevice keys of the open library's attachments whose file is on this device, as
 *   last worked out (LibraryDeviceState)
 * @param onFilesChanged called when files on the device may have changed (downloaded,
 *   uploaded, deleted), so the screen's attachment states are worked out again
 */
class LibraryAttachments(
    private val context: Context,
    private val ui: LibraryUi,
    private val library: OpenLibrary,
    private val api: () -> ZoteroAPI,
    private val attachmentStorageManager: AttachmentStorageManager,
    private val preferences: PreferenceManager,
    private val editedAttachments: EditedAttachments,
    private val uploader: AttachmentUploader,
    private val zoteroDatabase: ZoteroDatabase,
    private val onDevice: () -> Set<String>,
    private val onFilesChanged: () -> Unit,
) {
    private val state get() = library.state
    private val zoteroDB get() = library.db

    fun openPDF(attachment: Item) {
        /* This method creates an intent to open a PDF for Android. */

        run {
            try {
                val intent = attachmentStorageManager.openAttachment(attachment)
                ui.startActivity(intent)
            } catch (exception: ActivityNotFoundException) {
                ui.createYesNoPrompt("No app opens this file",
                    "Nothing on this device opens .${attachment.getFileExtension()} files. Install a PDF reader such as Xodo?",
                    "Get Xodo", "Cancel", onYesClick = {
                        try {
                            ui.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.xodo.pdf.reader")
                                )
                            )
                        } catch (e: ActivityNotFoundException) {
                            ui.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=com.xodo.pdf.reader")
                                )
                            )
                        }
                    }, onNoClick = {})
            } catch (e: IllegalArgumentException) {
                ui.createErrorAlert("Couldn't open the file",
                    "Android refused access to the file. This is a known problem on Huawei " +
                            "devices: in Settings, set Attachment folder to A folder you choose.",
                    {})
            } catch (e: FileNotFoundException) {
                ui.createErrorAlert("File not found",
                    "The file is missing from this device. Download it again.",
                    {})
            }
        }
    }

    fun openAttachment(item: Item) {
        /* This is the point of entry when a user clicks an attachment on the UI.
        *  We must decide whether we want to intitiate a download or just open a local copy. */

        // first check to see if we are opening a linked attachment
        if (item.data["linkMode"] == "linked_file") {
            val intent = attachmentStorageManager.openLinkedAttachment(item)
            if (intent != null) {
                ui.startActivity(intent)
            } else {
                ui.makeToastAlert("Linked file not found: ${item.data["path"]}")
            }
            return
        }
        ui.showLoadingAlertDialog("Opening…")
        // check to see if the attachment exists but is invalid
        val attachmentExists: Boolean
        try {
            attachmentExists = attachmentStorageManager.checkIfAttachmentExists(
                item,
                checkMd5 = false
            )
        } catch (e: Exception) {
            ui.hideLoadingAlertDialog()
            ui.makeToastAlert("File not found on this device.")
            return
        }
        if (attachmentExists) {
            ui.showLoadingAlertDialog("Checking for a newer version…")
            latestZoteroMd5(item)
                .observeOn(Schedulers.io())
                .map { latest -> Pair(latest, if (latest.isPresent) localCopyState(item) else null) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ (latest, local) ->
                    ui.hideLoadingAlertDialog()
                    if (!latest.isPresent || local == null) {
                        ui.makeToastAlert("Offline: opening the copy on this device.")
                        openPDF(item)
                    } else {
                        openLocalCopy(item, latest.get(), local)
                    }
                }, { e ->
                    ui.hideLoadingAlertDialog()
                    Log.e("zotero", "checking for a newer version failed: $e")
                    openPDF(item)
                })
        } else {
            ui.hideLoadingAlertDialog()
            ui.updateAttachmentDownloadProgress(0, -1)
            downloadAttachment(item)
        }

    }

    /**
     * The attachment's md5 on Zotero's server now ("" if it has no file), or null if the
     * server can't be asked: no network, a group library (the request is for My Library),
     * or no answer within a few seconds. One API request.
     */
    private fun latestZoteroMd5(item: Item): Single<Optional<String>> =
        if (state.isUsingGroup() || !hasNetwork()) {
            Single.just(Optional.empty())
        } else {
            api().getAttachmentFileState(item.itemKey)
                .map { (_, md5) -> Optional.of(md5 ?: "") }
                .timeout(5, TimeUnit.SECONDS)
                .onErrorReturn { e ->
                    Log.d("zotero", "no latest md5 for ${item.itemKey}: $e")
                    Optional.empty()
                }
                .subscribeOn(Schedulers.io())
        }

    /**
     * Whether Android reports a connected network. Checked before asking the server so
     * that offline opens are instant: a request on a dropped connection otherwise waits
     * for the timeout.
     */
    @Suppress("DEPRECATION") // activeNetworkInfo is the API 24 way
    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return cm.activeNetworkInfo?.isConnected == true
    }

    /** The local copy's md5 ("" if unreadable) and its base; hashes the file, so not on main. */
    private data class LocalCopyState(val md5: String, val base: String?)

    private fun localCopyState(item: Item) = LocalCopyState(
        runCatching { attachmentStorageManager.calculateMd5(item) }.getOrDefault(""),
        attachmentBase(item)
    )

    /** Opens the local copy, first replacing it if Zotero's [latestMd5] moved on. */
    private fun openLocalCopy(item: Item, latestMd5: String, local: LocalCopyState) {
        // Is the local copy based on Zotero's current version? See AttachmentOpenCheck.
        val localMd5 = local.md5
        val base = local.base
        when (AttachmentOpenCheck.decide(base, latestMd5, localMd5)) {
            AttachmentOpenCheck.Action.REDOWNLOAD -> {
                ui.makeToastAlert("Zotero has a newer version of this file. Downloading it.")
                ui.updateAttachmentDownloadProgress(0, -1)
                downloadAttachment(item) // replaces the copy once it's in
                return
            }
            AttachmentOpenCheck.Action.ASK_EDITED_STALE -> {
                ui.createYesNoPrompt(
                    "Newer version in Zotero",
                    "This file changed in Zotero after you downloaded it, and your copy has " +
                            "edits of its own. Your copy can't be uploaded without overwriting the " +
                            "newer version.\n\nDownload the newer version (your edits are " +
                            "replaced), or open your copy and save your annotations as a new file.",
                    "Download newer", "Open my copy", {
                        ui.updateAttachmentDownloadProgress(0, -1)
                        downloadAttachment(item)
                    }, {
                        openPDF(item)
                    }
                )
                return
            }
            AttachmentOpenCheck.Action.RECORD_BASE_AND_OPEN -> recordBase(item, localMd5)
            AttachmentOpenCheck.Action.OPEN -> {}
        }
        // A copy without a base can't be judged by AttachmentOpenCheck; offer Zotero's
        // version if it differs. With a base, a difference left here is this device's own
        // edit waiting to be uploaded, which "redownload" would throw away.
        if (base == null && latestMd5.isNotEmpty() && preferences.shouldCheckMd5SumBeforeOpening() &&
            !localMd5.equals(latestMd5, ignoreCase = true)
        ) {
            ui.createYesNoPrompt(
                "Different from Zotero's copy",
                "The file on this device differs from the one in Zotero. Download Zotero's version?",
                "Download", "Open mine", {
                    ui.updateAttachmentDownloadProgress(0, -1)
                    downloadAttachment(item)
                }, {
                    openPDF(item)
                }
            )
            return
        }
        openPDF(item)
    }

    private var downloadDisposable: Disposable? = null

    /**
     * Puts [item]'s file on the device without opening it. A copy that's already there is
     * checked against Zotero's current version (when online) and kept, unless it's an
     * unedited copy of an older version (replaced) or an edited copy of one (asks, as
     * opening does).
     */
    fun downloadAttachmentOnly(item: Item) {
        if (item.data["linkMode"] == "linked_file" || item.data["linkMode"] == "linked_url") {
            ui.makeToastAlert("Linked attachments aren't stored in Zotero.")
            return
        }
        val exists = try {
            attachmentStorageManager.checkIfAttachmentExists(item, checkMd5 = false)
        } catch (e: Exception) {
            false
        }
        if (!exists) {
            ui.updateAttachmentDownloadProgress(0, -1)
            downloadAttachment(item, openWhenDone = false)
            return
        }
        latestZoteroMd5(item)
            .observeOn(Schedulers.io())
            .map { latest -> Pair(latest, if (latest.isPresent) localCopyState(item) else null) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (latest, local) ->
                if (latest.isPresent && local != null) {
                    downloadIfOutdated(item, latest.get(), local)
                } else {
                    ui.makeToastAlert("Offline: keeping the copy on this device.")
                }
            }, { e -> Log.e("zotero", "checking for a newer version failed: $e") })
    }

    private fun downloadIfOutdated(item: Item, latestMd5: String, local: LocalCopyState) {
        val localMd5 = local.md5
        when (AttachmentOpenCheck.decide(local.base, latestMd5, localMd5)) {
            AttachmentOpenCheck.Action.REDOWNLOAD -> {
                ui.updateAttachmentDownloadProgress(0, -1)
                downloadAttachment(item, openWhenDone = false)
            }
            AttachmentOpenCheck.Action.ASK_EDITED_STALE -> ui.createYesNoPrompt(
                "Newer version in Zotero",
                "This file changed in Zotero after you downloaded it, and your copy has " +
                        "edits of its own. Downloading the newer version replaces your edits.",
                "Download newer", "Keep my copy", {
                    ui.updateAttachmentDownloadProgress(0, -1)
                    downloadAttachment(item, openWhenDone = false)
                }, {}
            )
            AttachmentOpenCheck.Action.RECORD_BASE_AND_OPEN -> {
                recordBase(item, localMd5)
                ui.makeToastAlert("Already on this device.")
            }
            AttachmentOpenCheck.Action.OPEN -> ui.makeToastAlert("Already on this device.")
        }
    }

    fun downloadAttachment(item: Item) = downloadAttachment(item, openWhenDone = true)

    /**
     * Downloads [item]'s file, then checks it ([verifyDownload]). Online, Zotero's current
     * md5 is asked for first: the one from the last sync is stale for a file that changed
     * in Zotero since (edited on another device, or uploaded from this one), and checking
     * against it threw the new file away as damaged until the next sync.
     */
    fun downloadAttachment(item: Item, openWhenDone: Boolean) {
        if (isDownloading) {
            Log.d("zotero", "not downloading ${item.getTitle()} because i am already downloading.")
            return
        }
        isDownloading = true

        // "" (no file in Zotero) tells nothing about the file the download brings
        latestZoteroMd5(item)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe { downloadDisposable = it } // cancellable while it asks
            .subscribe({ latest ->
                startDownload(item, openWhenDone, latest.orElse("").ifEmpty { null })
            }, { e ->
                Log.e("zotero", "asking for the latest md5 failed: $e") // not expected: it falls back
                startDownload(item, openWhenDone, null)
            })
    }

    /** [latestMd5]: Zotero's md5 for the file now, or null if it couldn't be asked. */
    private fun startDownload(item: Item, openWhenDone: Boolean, latestMd5: String?) {
        api().downloadItemRx(item, state.currentGroup.id)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<DownloadProgress> {
                var mtime = 0L

                override fun onComplete() {
                    isDownloading = false
                    ui.finishDownloadingAttachment()
                    verifyDownload(item, openWhenDone, latestMd5 ?: zoteroDB.getMd5Key(item), mtime)
                }

                override fun onSubscribe(d: Disposable) {
                    downloadDisposable = d

                }

                override fun onNext(t: DownloadProgress) {
                    ui.updateAttachmentDownloadProgress(t.progress, t.total)
                    mtime = t.mtime
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "got error ${e}")
                    if (e is ZoteroNotFoundException) {
                        ui.attachmentDownloadError(
                            "The file isn't in Zotero's storage (or on your WebDAV server)."
                        )
                    } else {
                        ui.attachmentDownloadError(
                            "Check your connection and try again.\n\nDetails: ${e.message}"
                        )
                    }
                    isDownloading = false
                }

            })
    }


    /**
     * Checks a finished download against Zotero's md5 and records it (as the copy's base,
     * and its md5 and [mtime]), hashing the file once, off the main thread; then opens it
     * or says it's there. A damaged download is deleted and recorded nowhere, as in a bulk
     * download (AttachmentDownloadWorker).
     */
    private fun verifyDownload(item: Item, openWhenDone: Boolean, expected: String, mtime: Long) {
        // the md5 if the file is intact, else ""
        Single.fromCallable {
            val md5 = attachmentStorageManager.calculateMd5(item)
            val intact = expected.isEmpty() || md5.equals(expected, ignoreCase = true)
            if (intact) recordBase(item, md5)
            if (intact) md5 else ""
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ md5 ->
                val intact = md5.isNotEmpty()
                if (intact) {
                    // on the main thread: it also updates the open library's records in memory
                    val source = if (preferences.isWebDAVEnabled()) AttachmentInfo.WEBDAV else AttachmentInfo.ZOTEROAPI
                    zoteroDB.updateAttachmentMetadata(item.itemKey, md5, mtime, source)
                        .subscribeOn(Schedulers.io()).subscribe({}, { Log.e("zotero", "recording ${item.itemKey}'s download failed: $it") })
                }
                if (!intact) {
                    Log.d("zotero", "md5 error on attachment $expected")
                    attachmentStorageManager.deleteAttachment(item)
                    ui.createErrorAlert(
                        "Download damaged",
                        "The downloaded file doesn't match Zotero's checksum, so it was " +
                                "deleted. Try again.",
                        {})
                    return@subscribe
                }
                onFilesChanged()
                if (openWhenDone) {
                    openPDF(item)
                } else {
                    ui.makeToastAlert("Downloaded ${item.data["filename"] ?: item.getTitle()}.")
                }
            }, { e ->
                Log.e("zotero", "checking the download of ${item.itemKey} failed: $e")
                ui.attachmentDownloadError("The downloaded file can't be read.\n\nDetails: ${e.message}")
            })
    }

    fun cancelAttachmentDownload() {
        Log.d("zotero", "cancelling download")
        this.isDownloading = false
        downloadDisposable?.dispose()
    }

    private var isDownloading: Boolean = false

    fun uploadAttachment(attachment: Item) {
        uploadAttachments(listOf(attachment))
    }

    /** "Upload my changes" for one attachment: uploads it only if it differs from its base. */
    fun uploadChanges(item: Item) {
        Single.fromCallable {
            AttachmentLocalState.of(
                editedAttachments.currentMd5(item.itemKey), attachmentBase(item), zoteroDB.getMd5Key(item)
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                when (state) {
                    AttachmentLocalState.NOT_DOWNLOADED ->
                        ui.makeToastAlert("This file isn't on this device.")
                    AttachmentLocalState.ON_DEVICE, AttachmentLocalState.OUTDATED ->
                        if (attachmentBase(item) != null) {
                            ui.makeToastAlert("No changes to upload.")
                        } else {
                            uploadAttachments(listOf(item)) // can't tell; upload as asked
                        }
                    // CONFLICT goes through too: the preflight refuses it and says why
                    AttachmentLocalState.EDITED, AttachmentLocalState.CONFLICT ->
                        uploadAttachments(listOf(item))
                }
            }, { e -> Log.e("zotero", "checking ${item.itemKey} for changes failed: $e") })
    }

    private var uploadDisposable: Disposable? = null

    /**
     * Uploads [attachments] one after another and reports once at the end. A file that is
     * refused (changed elsewhere, damaged) or fails doesn't stop the others; it stays in
     * RecentlyOpenedAttachment, so it is offered again.
     */
    fun isUploading() = uploadDisposable?.isDisposed == false

    fun uploadAttachments(attachments: List<Item>, onDone: (() -> Unit)? = null) {
        if (attachments.isEmpty()) return
        if (isUploading()) {
            ui.makeToastAlert("Already uploading attachments.")
            return
        }
        val total = attachments.size
        uploadDisposable = Observable.fromIterable(attachments.withIndex())
            .concatMapSingle { (index, attachment) ->
                val name = attachment.data["filename"] ?: attachment.itemKey
                Completable.fromAction {
                    ui.startUploadingAttachmentProgress(attachment, index + 1, total)
                }.subscribeOn(AndroidSchedulers.mainThread())
                    .andThen(uploader.upload(attachment).subscribeOn(Schedulers.io()))
                    .toSingleDefault(AttachmentUploadBatch.Outcome(name))
                    .onErrorReturn { e ->
                        Log.e("zotero", "upload of ${attachment.itemKey} failed: $e")
                        AttachmentUploadBatch.outcome(name, e)
                    }
            }
            .toList()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ outcomes ->
                ui.stopUploadingAttachmentProgress()
                val summary = AttachmentUploadBatch.summary(outcomes)
                if (summary.quiet) {
                    ui.makeToastAlert(summary.message)
                } else {
                    ui.createErrorAlert(summary.title, summary.message, {})
                }
                onFilesChanged()
                onDone?.invoke()
            }, { e ->
                Log.e("zotero", "upload batch failed: $e")
                ui.stopUploadingAttachmentProgress()
                ui.createErrorAlert("Upload failed", "Details: ${e.message ?: e}", {})
                onFilesChanged()
                onDone?.invoke()
            })
    }

    /**
     * Attachments in My Library edited on this device and not uploaded yet; see
     * EditedAttachments. Hashes files whose size or mtime changed, so call it off the main
     * thread.
     */
    fun findEditedAttachments(): List<Item> {
        if (state.isUsingGroup() || !preferences.isAttachmentUploadingEnabled() || !library.isLoaded()) return emptyList()
        return editedAttachments.edited()
    }

    /** Where [item]'s file stands on this device; worked out off the main thread. */
    fun describeAttachment(item: Item, onState: (AttachmentLocalState) -> Unit) {
        Single.fromCallable {
            AttachmentLocalState.of(
                editedAttachments.currentMd5(item.itemKey), attachmentBase(item), zoteroDB.getMd5Key(item)
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ onState(it) }, { e -> Log.e("zotero", "describing ${item.itemKey} failed: $e") })
    }

    /** md5 of the file this device last downloaded/uploaded for [item]; see AttachmentBase. */
    private fun attachmentBase(item: Item): String? = editedAttachments.base(item)

    private fun recordBase(item: Item, md5: String) = editedAttachments.recordBase(item, md5)

    /**
     * The attachments of the items [keys] (and any of them that are attachments), by kind,
     * split into those on this device and those not; [onResult] on the main thread.
     */
    fun countAttachments(keys: List<String>, onResult: (Map<AttachmentKind, AttachmentCounts>) -> Unit) {
        ui.showLoadingAlertDialog("Looking at the attachments…")
        val here = onDevice()
        Single.fromCallable { AttachmentCounts.of(zoteroDatabase.loadAttachmentsOf(keys)) { it.itemKey in here } }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ counts ->
                ui.hideLoadingAlertDialog()
                onResult(counts)
            }, { e ->
                ui.hideLoadingAlertDialog()
                Log.e("zotero", "reading the attachments of ${keys.size} items failed: $e")
                ui.createErrorAlert("Couldn't read the attachments", "Details: ${e.message ?: e}") {}
            })
    }

    /**
     * Downloads [attachments] in the background (AttachmentDownloads), one after another;
     * the app can be left meanwhile. Progress shows in the toolbar.
     */
    fun downloadAttachments(attachments: List<Item>) {
        val group = state.currentGroup.id
        val added = AttachmentDownloads.enqueue(context, attachments.map { AttachmentDownloadQueue.Entry(group, it.itemKey) })
        ui.makeToastAlert(
            when (added) {
                0 -> "They're being downloaded already."
                1 -> "Downloading 1 file in the background."
                else -> "Downloading $added files in the background."
            }
        )
    }

    /** Those of [files] (on this device) with edits not uploaded yet; [onResult] on the main thread. */
    fun findEdited(files: List<Item>, onResult: (List<Item>) -> Unit) {
        ui.showLoadingAlertDialog("Checking for edits…")
        Single.fromCallable { files.filter { editedAttachments.isEdited(it) } }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ edited ->
                ui.hideLoadingAlertDialog()
                onResult(edited)
            }, { e ->
                ui.hideLoadingAlertDialog()
                Log.e("zotero", "checking ${files.size} files for edits failed: $e")
                ui.createErrorAlert("Couldn't check the files for edits", "Nothing was deleted.\n\nDetails: ${e.message ?: e}") {}
            })
    }

    /** Deletes the copies of [files] on this device; Zotero keeps them. */
    fun deleteLocalAttachments(files: List<Item>) {
        Single.fromCallable { files.count { runCatching { attachmentStorageManager.deleteAttachment(it) }.getOrDefault(false) } }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ deleted ->
                onFilesChanged()
                ui.makeToastAlert(if (deleted == 1) "Removed 1 file from this device." else "Removed $deleted files from this device.")
            }, { e ->
                onFilesChanged()
                Log.e("zotero", "deleting ${files.size} files failed: $e")
                ui.createErrorAlert("Couldn't remove the files", "Details: ${e.message ?: e}") {}
            })
    }

    fun deleteLocalAttachment(attachment: Item) {
        val delete = {
            attachmentStorageManager.deleteAttachment(attachment)
            onFilesChanged()
            ui.makeToastAlert("Removed from this device: ${attachment.getTitle()}")
        }
        // in a folder the user chose the file is theirs: always ask, naming the folder
        Single.fromCallable {
            Pair(editedAttachments.isEdited(attachment), Optional.ofNullable(attachmentStorageManager.chosenFolderName()))
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (edited, folder) ->
                val inFolder = folder.orElse(null)
                    ?.let { SelectionDialogs.deletedFromFolder(it, 1) }
                when {
                    edited -> ui.createYesNoPrompt(
                        "Remove your edits?",
                        "This copy has edits that haven't been uploaded. Removing it loses them." +
                                (inFolder?.let { "\n\n$it" } ?: ""),
                        "Remove", "Keep", delete, {}
                    )
                    inFolder != null -> ui.createYesNoPrompt(
                        "Remove from this device?",
                        "$inFolder\n\nIt stays in Zotero and can be downloaded again.",
                        "Remove", "Keep", delete, {}
                    )
                    else -> delete()
                }
            }, { e -> Log.e("zotero", "checking ${attachment.itemKey} for edits failed: $e") })
    }
}
