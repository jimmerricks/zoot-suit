package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.ZoteroAPI.Model.ItemPOJO

data class ZoteroAPIItemsResponse(
    val items: List<ItemPOJO>,
    val LastModifiedVersion: Int,
    val totalResults: Int
)