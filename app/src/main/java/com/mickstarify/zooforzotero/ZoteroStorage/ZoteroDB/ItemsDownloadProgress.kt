package com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB

import com.mickstarify.zooforzotero.ZoteroAPI.ItemScope

data class ItemsDownloadProgress(
    val libraryVersion: Int,
    val nDownloaded: Int,
    val total: Int, //tbh useless
    // which listing nDownloaded counts into; CHILDREN may be at 0, its pass not begun
    val scope: ItemScope = ItemScope.ALL
)
