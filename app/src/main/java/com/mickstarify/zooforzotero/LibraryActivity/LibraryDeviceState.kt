package com.mickstarify.zooforzotero.LibraryActivity

import android.util.Log
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * What the open library has on this device and what waits to sync: attachments whose
 * file is here, those edited here, and items with metadata edits. Shown as the toolbar's
 * edit count and the list's PDF icons, and read by the list's filters ([current]).
 *
 * @param findEditedAttachments edited attachments that would be uploaded; blocking
 * @param filterNeedsDeviceState whether the open list's filter depends on [current]
 */
class LibraryDeviceState(
    private val ui: LibraryUi,
    private val library: OpenLibrary,
    private val editedAttachments: EditedAttachments,
    private val findEditedAttachments: () -> List<Item>,
    private val itemEdits: ItemEditStore,
    private val filterNeedsDeviceState: () -> Boolean,
) {
    @Volatile
    var current = DeviceState()
        private set

    fun hasPendingEdits(item: Item) = !library.state.isUsingGroup() && item.itemKey in current.itemEdits

    /**
     * Works out, off the main thread, which attachments are on the device and which have
     * edits to sync; shows the edit count in the toolbar and both on the list's PDF icons,
     * and redraws the list if the filter depends on either and they changed. Then [onDone].
     */
    fun refresh(onDone: () -> Unit = {}) {
        Single.fromCallable {
            DeviceState(
                onDevice = if (library.isLoaded()) editedAttachments.onDeviceKeys() else emptySet(),
                edited = findEditedAttachments().map { it.itemKey }.toSet(),
                itemEdits = itemEdits.editedItemKeys(GroupInfo.NO_GROUP_ID),
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ now ->
                val changed = now != current
                current = now
                ui.showPendingEdits(now.edited.size + now.itemEdits.size)
                ui.showAttachmentStates(now.onDevice, now.edited)
                if (changed && filterNeedsDeviceState()) ui.redisplayItems()
                onDone()
            }, { e ->
                Log.e("zotero", "checking attachments on the device failed: $e")
                onDone()
            })
    }
}
