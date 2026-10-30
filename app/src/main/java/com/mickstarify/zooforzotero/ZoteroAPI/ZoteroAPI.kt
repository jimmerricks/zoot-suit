package com.mickstarify.zooforzotero.ZoteroAPI

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mickstarify.zooforzotero.BuildConfig
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroAPI.Model.CollectionPOJO
import com.mickstarify.zooforzotero.ZoteroAPI.Model.DeletedEntriesPojo
import com.mickstarify.zooforzotero.ZoteroAPI.Model.ItemJSONConverter
import com.mickstarify.zooforzotero.ZoteroAPI.Model.ItemPOJO
import com.mickstarify.zooforzotero.ZoteroAPI.Model.KeyInfo
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroAPI.Model.ZoteroUploadAuthorizationPojo
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ItemsDownloadProgress
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.ObservableEmitter
import io.reactivex.rxjava3.core.ObservableOnSubscribe
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.LinkedList
import java.util.concurrent.TimeUnit


const val BASE_URL = "https://api.zotero.org"

class UpToDateException(message: String) : RuntimeException(message)
class LibraryVersionMisMatchException(message: String) : RuntimeException(message)
class APIKeyRevokedException(message: String) : RuntimeException(message)
class AlreadyUploadedException(message: String) : RuntimeException(message)
class PreconditionFailedException(message: String) : RuntimeException(message)
class RequestEntityTooLarge(message: String) : RuntimeException(message)
class ServerAlreadyExistsException(message: String) : RuntimeException(message)
class ItemLockedException(message: String) : RuntimeException(message)
class ItemChangedSinceException(message: String) : RuntimeException(message)
class ItemGoneException(message: String) : RuntimeException(message)
class WriteAccessDeniedException(message: String) : RuntimeException(message)
class ZoteroNotFoundException(message: String) : RuntimeException(message)

// The Zotero Web API's maximum page size; it defaults to 25 when omitted.
// Pagination advances by the number of items actually returned, so any value works.
private const val ITEMS_PAGE_SIZE = 100

/** Objects per multi-object write, and keys per itemKey= read: the Zotero Web API's limit for both. */
const val MAX_ITEMS_PER_WRITE = 50

// Item pages requested at once during a sync: Zotero's documented ceiling of 4 concurrent
// requests, which ZoteroRateLimitInterceptor enforces (and lowers to 1 after a 429).
// Measured on an 18,300-item library: 1 → ~2,500 items/min, 4 → ~10,300, no Backoff or 429.
private const val ITEMS_PARALLEL_REQUESTS = ZoteroRateLimitPolicy.MAX_CONCURRENT

// Item types the sync skips. The app never reads PDF annotations (Zotero 6+ stores
// each highlight/note-on-PDF as its own child item), and in an annotated library they
// can be a large share of all items. Also stored with resumable download progress:
// an offset into a differently filtered listing is meaningless.
const val SYNC_ITEM_TYPE_FILTER = "-annotation"

/**
 * Which items a download asks for. A first sync fetches [TOP] and then [CHILDREN], so the
 * library can be used once its top-level items are in (about a quarter of a library with
 * many attachments and notes); later syncs fetch [ALL] that changed. [stored] goes with
 * resumable download progress; [ALL]'s is what versions before the split stored.
 */
enum class ItemScope(val stored: String, val itemType: String?) {
    ALL(SYNC_ITEM_TYPE_FILTER, SYNC_ITEM_TYPE_FILTER),
    TOP("top", null),
    // Zotero's search syntax: either type. Top-level (standalone) attachments and notes
    // come again here; writing them twice is harmless.
    CHILDREN("attachment || note", "attachment || note");

    companion object {
        fun fromStored(stored: String?): ItemScope? = entries.firstOrNull { it.stored == stored }
    }
}

class ZoteroAPI(
    val API_KEY: String,
    val userID: String,
    val username: String,
    val attachmentStorageManager: AttachmentStorageManager,
    val preferencesManager: PreferenceManager,
    // tests point this at a MockWebServer
    private val baseUrl: String = BASE_URL
) {
    private val baseHttpClient = OkHttpClient().newBuilder().apply {
        if (BuildConfig.DEBUG) {
            addInterceptor(HttpLoggingInterceptor().apply {
                this.level = HttpLoggingInterceptor.Level.BASIC
            })
        }
    }.build()

    // shared by both API clients so a Backoff from one also delays the other
    private val rateLimitInterceptor = ZoteroRateLimitInterceptor()

    // The key goes as "Authorization: Bearer", not "Zotero-API-Key": a file download is
    // redirected to Zotero's storage host (S3), and OkHttp drops only Authorization when
    // a redirect leaves the host, so the other header went along to S3.
    private val zoteroHeaders = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .addHeader("Zotero-API-Version", "3")
                .addHeader("Authorization", "Bearer $API_KEY")
                .build()
        )
    }

    private val zoteroHttpClient = baseHttpClient
        .newBuilder().apply {
            addInterceptor(rateLimitInterceptor)
            writeTimeout(
                preferencesManager.getHttpWriteTimeout(),
                TimeUnit.MILLISECONDS
            ) // so socket doesn't timeout on large uploads (attachments)
            addInterceptor(zoteroHeaders)
        }
        .build()

    private val zoteroHttpClientShortTimeout = baseHttpClient
        .newBuilder().apply {
            addInterceptor(rateLimitInterceptor)
            addInterceptor(zoteroHeaders)
            // connect fails fast when offline; reads get longer because a 100-item page
            // from a large library can take the server well over 5 s on a cold query.
            connectTimeout(5000, TimeUnit.MILLISECONDS)
            readTimeout(30000, TimeUnit.MILLISECONDS)
            writeTimeout(5000, TimeUnit.MILLISECONDS)
        }
        .build()

    // no Zotero headers: it posts to the storage URLs Zotero hands out (S3)
    private val httpClientForAttachments = baseHttpClient
        .newBuilder()
        .apply {
            writeTimeout(
                10,
                TimeUnit.MINUTES
            ) // so socket doesn't timeout on large uploads (attachments)
        }
        .build()

    private fun serviceWith(client: OkHttpClient): ZoteroAPIService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .build().create(ZoteroAPIService::class.java)

    val service = serviceWith(zoteroHttpClient)
    val attachmentsService = serviceWith(httpClientForAttachments)
    val shortTimeoutService = serviceWith(zoteroHttpClientShortTimeout)

    private var webdavClient: Pair<String, Webdav>? = null

    /**
     * The WebDAV client for the current settings. One per settings, not one per call:
     * every Webdav builds its own OkHttp client, so each download, .prop check and
     * upload used to open a new connection (TLS handshake, auth challenge) to the server.
     */
    @Synchronized
    private fun webdav(): Webdav {
        val p = preferencesManager
        val settings = listOf(
            p.getWebDAVAddress(), p.getWebDAVUsername(), p.getWebDAVPassword(),
            p.getVerifySSLForWebDAV(), p.getWebDAVAuthMode(), p.getWebDAVConnectTimeout(),
            p.getWebDAVReadTimeout(), p.getWebDAVWriteTimeout(),
            p.isWebDAVBackupBeforeUploadEnabled()
        ).joinToString("\u0000")
        webdavClient?.let { (key, client) -> if (key == settings) return client }
        return Webdav(p).also { webdavClient = Pair(settings, it) }
    }

    fun downloadItemRx(
        item: Item,
        groupID: Int = GroupInfo.NO_GROUP_ID,
        context: Context
    ): Observable<DownloadProgress> {

        val useGroup = groupID != GroupInfo.NO_GROUP_ID
        if (item.itemType != "attachment") {
            Log.d("zotero", "got download request for item that isn't attachment")
        }

        // we will delegate to a webdav download if the user has webdav enabled.
        // When the user has webdav enabled on personal account or for groups are the only two conditions.
        if (!useGroup && preferencesManager.isWebDAVEnabled()) {
            return webdav().downloadFileRx(item, context, attachmentStorageManager)
            //stops here.
        }


        val observable = Observable.create<DownloadProgress> { emitter ->

            val downloader = if (!useGroup) {
                service.getFileForUser(userID, item.itemKey)
            } else {
                service.getFileForGroup(groupID, item.itemKey)
            }
            downloader.subscribe(object : Observer<Response<ResponseBody>> {
                override fun onComplete() {
                    emitter.onComplete()
                }

                override fun onSubscribe(d: Disposable) {
                    // do nothing.
                }

                override fun onNext(response: Response<ResponseBody>) {
                    if (emitter.isDisposed == true) {
                        Log.d("zotero", "download was cancelled, not writing.")
                        return
                    }

                    val body = response.body()
                    val fileSize = body?.contentLength() ?: 0
                    if (response.code() == 200 && body != null) {
                        val md5Hash = item.data["md5"] ?: ""
                        val mtime = (item.data["mtime"] ?: "0").toLong()
                        // opened only now, so a failed request leaves an existing copy
                        // alone; closed on every path, which also flushes it
                        attachmentStorageManager.getItemOutputStream(item).use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(65536)
                                val reporter = ProgressThrottle()
                                var progress: Long = 0
                                var read = input.read(buffer)
                                // a cancelled download stops here; left running, a retry
                                // wrote the same file alongside it
                                while (read > 0 && !emitter.isDisposed) {
                                    out.write(buffer, 0, read)
                                    progress += read
                                    if (reporter.due()) {
                                        emitter.onNext(
                                            DownloadProgress(
                                                progress = progress,
                                                total = fileSize,
                                                metadataHash = md5Hash,
                                                mtime = mtime
                                            )
                                        )
                                    }
                                    read = input.read(buffer)
                                }
                                emitter.onNext(
                                    DownloadProgress(progress = progress, total = fileSize, metadataHash = md5Hash, mtime = mtime)
                                )
                            }
                        }
                        if (emitter.isDisposed) attachmentStorageManager.deleteAttachment(item) // partial
                        emitter.onComplete()
                    } else if (response.code() == 404) {
                        throw ZoteroNotFoundException("Not found on server.")
                    } else {
                        Log.e("zotero", "network error. response: ${response.body()}")
                        throw RuntimeException("Invalid server response code ${response.code()}")
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "download attachment got error $e  ${emitter.isDisposed}")
                    attachmentStorageManager.deleteAttachment(item)
                    if (!emitter.isDisposed) {
                        emitter.onError(e)
                    }
                }
            })
        }
        return observable
    }

    fun testConnection(callback: (success: Boolean, message: String) -> (Unit)) {
        val call: Call<KeyInfo> = service.getKeyInfo(this.API_KEY)

        call.enqueue(object : Callback<KeyInfo> {
            override fun onResponse(call: Call<KeyInfo>, response: Response<KeyInfo>) {
                if (response.code() == 200) {
                    Log.d("zotero", "Successfully tested connection.")
                    callback(true, "")
                } else {
                    callback(
                        false,
                        "error got back response code ${response.code()} body: ${response.body()}"
                    )
                }
            }

            override fun onFailure(call: Call<KeyInfo>, t: Throwable) {
                Log.d("zotero", "failure on item")
                callback(false, "Failure.")
            }
        })
    }

    fun modifyNote(note: Note, libraryVersion: Int): Completable {
        val observable =
            service.patchItem(userID, note.key, note.getJsonNotePatch(), note.version)
                .map { response ->
                    if (response.code() == 200 || response.code() == 204) {
                        Log.d("zotero", "success on note modification")
                    } else if (response.code() == 409) {
                        throw ItemLockedException("Failed to upload note.")
                    } else if (response.code() == 412) {
                        throw ItemChangedSinceException("item out of date, please sync first.")
                    } else {
                        Log.d("zotero", "got back code ${response.code()} from note upload.")
                        throw Exception("Server gave back code ${response.code()}")
                    }
                    response
                }
        return Completable.fromObservable(observable)
    }

    fun getDeletedEntries(
        sinceVersion: Int,
        groupID: Int = GroupInfo.NO_GROUP_ID
    ): Single<DeletedEntriesPojo> {
        val observable = when (groupID) {
            GroupInfo.NO_GROUP_ID -> {
                service.getDeletedEntriesSince(userID, sinceVersion)
            }
            else -> {
                service.getDeletedEntriesForGroupSince(groupID, sinceVersion)
            }
        }
        return Single.fromObservable(observable.map { response ->
            if (response.code() == 200) {
                response.body() ?: throw Exception("Response body is null")
            } else {
                throw Exception("Error server responded with code ${response.code()}")
            }
        })
    }

    fun deleteItem(itemKey: String, version: Int, listener: DeleteItemListener) {
        val call: Call<ResponseBody> = service.deleteItem(userID, itemKey, version)

        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                when (response.code()) {
                    204 -> listener.success()
                    409 -> listener.failedItemLocked()
                    412 -> listener.failedItemChangedSince()
                    else -> listener.failed(response.code())
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                listener.failedNoConnection(t)
            }
        })
    }

    /**
     * The attachment item's current version and md5 on the server, md5 null if it
     * has no file. Used to check for remote changes before uploading.
     */
    fun getAttachmentFileState(itemKey: String): Single<Pair<Int, String?>> {
        return Single.fromObservable(service.getItem(userID, itemKey).map { response ->
            if (response.code() != 200) {
                throw Exception("Zotero server gave back code ${response.code()} for item $itemKey")
            }
            val root = Gson().fromJson(response.body()!!.string(), JsonObject::class.java)
            val md5 = root.getAsJsonObject("data")?.get("md5")
            Pair(root.get("version").asInt, if (md5 == null || md5.isJsonNull) null else md5.asString)
        })
    }

    /** The attachment's .prop on the WebDAV server (with ETag), null if there is none. */
    fun getWebdavRemoteProp(attachment: Item, context: Context): RemoteProp? {
        return webdav().getRemoteProp(attachment)
    }

    fun patchItem(item: Item, patch: JsonObject, lastModifiedVersion: Int = item.getVersion()): Completable {
        val observable =
            service.patchItem(userID, item.itemKey, patch, lastModifiedVersion).map { response ->
                if (response.code() == 200 || response.code() == 204) {
                    Log.d("zotero", "success on patch")
                } else if (response.code() == 409) {
                    throw ItemLockedException("You do not have write permission.")
                } else if (response.code() == 412) {
                    throw ItemChangedSinceException("Local item out of date, please sync first.")
                } else {
                    Log.d("zotero", "Zotero server gave code ${response.code()} for patch.")
                    throw Exception("Zotero server gave back code ${response.code()}")
                }
            }
        return Completable.fromObservable(observable)
    }

    /** The item as Zotero has it now, in My Library: its version and its "data" object. */
    fun getItemData(itemKey: String): Single<Pair<Int, JsonObject>> {
        return Single.fromObservable(service.getItem(userID, itemKey).map { response ->
            when (response.code()) {
                200 -> {}
                404 -> throw ItemGoneException("$itemKey is no longer in Zotero")
                else -> throw Exception("Zotero server gave back code ${response.code()} for item $itemKey")
            }
            val root = Gson().fromJson(response.body()!!.string(), JsonObject::class.java)
            Pair(root.get("version").asInt, root.getAsJsonObject("data"))
        })
    }

    /**
     * Creates [item] (a "data" object with its own "key" and "version" 0) in My Library.
     * Zotero answers 200 whether or not it was created: null if it was, else why not
     * (WriteResponse.Failure; 412 means the key exists already). Blocking.
     */
    fun createItem(item: JsonObject): WriteResponse.Failure? {
        val response = service.createItems(userID, JsonArray().apply { add(item) }).blockingFirst()
        when (response.code()) {
            200 -> {}
            403 -> throw WriteAccessDeniedException("This app's API key may only read the library.")
            409 -> throw ItemLockedException("The library is locked.")
            else -> throw Exception("Zotero server gave back code ${response.code()} creating an item")
        }
        return WriteResponse.firstFailure(response.body()?.string() ?: "")
    }

    /**
     * Up to [MAX_ITEMS_PER_WRITE] items of My Library as Zotero has them: by key, each its
     * version and "data" object. A key Zotero doesn't have (deleted for good) isn't in the
     * map. Blocking.
     */
    fun getItemsData(itemKeys: List<String>): Map<String, Pair<Int, JsonObject>> {
        require(itemKeys.size <= MAX_ITEMS_PER_WRITE)
        if (itemKeys.isEmpty()) return emptyMap()
        val response = service.getItemsByKey(userID, itemKeys.joinToString(",")).blockingFirst()
        if (response.code() != 200) {
            throw Exception("Zotero server gave back code ${response.code()} for ${itemKeys.size} items")
        }
        val items = Gson().fromJson(response.body()!!.string(), JsonArray::class.java)
        return items.mapNotNull { it as? JsonObject }.associate { root ->
            root.get("key").asString to Pair(root.get("version").asInt, root.getAsJsonObject("data"))
        }
    }

    /**
     * Writes up to [MAX_ITEMS_PER_WRITE] changes to items of My Library in one request, each
     * a partial "data" object with its "key" and the "version" it was read at (Zotero only
     * applies it to that version). Returns each one's outcome by index (WriteResponse.outcomes).
     * Blocking.
     */
    fun updateItems(changes: List<JsonObject>): List<WriteResponse.Failure?> {
        require(changes.size <= MAX_ITEMS_PER_WRITE)
        if (changes.isEmpty()) return emptyList()
        val body = JsonArray().apply { changes.forEach { add(it) } }
        val response = service.createItems(userID, body).blockingFirst()
        when (response.code()) {
            200 -> {}
            403 -> throw WriteAccessDeniedException("This app's API key may only read the library.")
            409 -> throw ItemLockedException("The library is locked.")
            else -> throw Exception("Zotero server gave back code ${response.code()} updating ${changes.size} items")
        }
        return WriteResponse.outcomes(response.body()?.string() ?: "", changes.size)
    }

    /**
     * Changes the properties in [patch] of an item in My Library, if Zotero's copy is still
     * at [version] (ItemChangedSinceException otherwise).
     */
    fun patchItemData(itemKey: String, patch: JsonObject, version: Int): Completable {
        val observable = service.patchItem(userID, itemKey, patch, version).map { response ->
            when (response.code()) {
                200, 204 -> {}
                403 -> throw WriteAccessDeniedException("This app's API key may only read the library.")
                404 -> throw ItemGoneException("$itemKey is no longer in Zotero")
                409 -> throw ItemLockedException("The library is locked.")
                412 -> throw ItemChangedSinceException("$itemKey changed in Zotero")
                else -> throw Exception("Zotero server gave back code ${response.code()} for item $itemKey")
            }
        }
        return Completable.fromObservable(observable)
    }

    private fun getItemsFromIndex(
        modificationSinceVersion: Int,
        index: Int,
        groupID: Int,
        scope: ItemScope
    ): Observable<ZoteroAPIItemsResponse> {
        val itemType = scope.itemType

        val observable = if (itemType == null) {
            val since = maxOf(modificationSinceVersion, 0)
            if (groupID != GroupInfo.NO_GROUP_ID) {
                shortTimeoutService.getTopItemsForGroup(since, groupID, since, index, ITEMS_PAGE_SIZE)
            } else {
                shortTimeoutService.getTopItems(since, userID, since, index, ITEMS_PAGE_SIZE)
            }
        } else if (modificationSinceVersion > -1) {
            if (groupID != GroupInfo.NO_GROUP_ID) {
                shortTimeoutService.getItemsForGroupSince(
                    modificationSinceVersion,
                    groupID,
                    modificationSinceVersion,
                    index,
                    ITEMS_PAGE_SIZE,
                    itemType
                )
            } else {
                shortTimeoutService.getItemsSince(
                    modificationSinceVersion,
                    userID,
                    modificationSinceVersion,
                    index,
                    ITEMS_PAGE_SIZE,
                    itemType
                )
            }
        } else {
            if (groupID != GroupInfo.NO_GROUP_ID) {
                shortTimeoutService.getItemsForGroup(0, groupID, index, ITEMS_PAGE_SIZE, itemType)
            } else {
                shortTimeoutService.getItems(0, userID, index, ITEMS_PAGE_SIZE, itemType)
            }

        }

        Log.d("zotero", "got since=$modificationSinceVersion")

        return observable.map { response: Response<ResponseBody> ->
            if (response.code() == 304) {
                throw UpToDateException("304 Items already loaded.")
            } else if (response.code() == 200) {
                val s = response.body()?.string() ?: "[]"
                val newItems = ItemJSONConverter().deserialize(s)
                val lastModifiedVersion = response.headers()["Last-Modified-Version"]?.toInt() ?: -1
                val totalResults = response.headers()["Total-Results"]?.toInt() ?: -1

                ZoteroAPIItemsResponse(newItems, lastModifiedVersion, totalResults)
            } else {
                throw Exception("Server gave back ${response.code()} message: ${response.body()}")
            }
        }
    }

    /** [itemKey]'s attachments and notes (the first [ITEMS_PAGE_SIZE]), in one request. */
    fun getChildItems(itemKey: String, groupID: Int): Single<List<ItemPOJO>> {
        val request = if (groupID != GroupInfo.NO_GROUP_ID) {
            shortTimeoutService.getChildItemsForGroup(groupID, itemKey, ITEMS_PAGE_SIZE)
        } else {
            shortTimeoutService.getChildItems(userID, itemKey, ITEMS_PAGE_SIZE)
        }
        return request.map { response ->
            if (response.code() != 200) throw Exception("Server gave back ${response.code()} for the children of $itemKey")
            ItemJSONConverter().deserialize(response.body()?.string() ?: "[]")
        }
    }

    fun getItems(
        libraryVersion: Int = -1,
        groupID: Int = GroupInfo.NO_GROUP_ID,
        downloadProgress: ItemsDownloadProgress? = null,
        scope: ItemScope = downloadProgress?.scope ?: ItemScope.ALL
    ): Observable<ZoteroAPIItemsResponse> = Observable.defer {
        val fromIndex = downloadProgress?.nDownloaded ?: 0
        Log.d("zotero", "getItemsFromIndex($libraryVersion, $fromIndex, $groupID)")
        val response = getItemsFromIndex(libraryVersion, fromIndex, groupID, scope).blockingFirst()
        Log.d("zotero", "got response $response")

        if (downloadProgress != null && response.LastModifiedVersion != downloadProgress.libraryVersion) {
            // Due to possible miss-syncs, we cannot continue the download.
            // we will raise an exception which will tell the activity to redownload without the
            // progress object.
            throw LibraryVersionMisMatchException("Cannot continue, our version ${downloadProgress.libraryVersion} doesn't match Server's ${response.LastModifiedVersion}")
        }

        // The rest of the pages are known once the first one is in. Each takes the server
        // 2-3 s whatever its size, so they're fetched a few at a time; concatMapEager still
        // emits them in order, so what SyncManager stores as resumable progress stays a
        // prefix of the listing. Steps by what the first page held, should the server
        // return fewer than asked for.
        val step = response.items.size
        val offsets = if (step == 0) emptyList()
        else (fromIndex + step until response.totalResults step step).toList()
        val rest = Observable.fromIterable(offsets)
            .concatMapEager({ index ->
                getItemsFromIndex(libraryVersion, index, groupID, scope).subscribeOn(Schedulers.io())
            }, ITEMS_PARALLEL_REQUESTS, 1)
            .map { res ->
                // fewer items than Total-Results promised: a library that changed meanwhile
                // starts over; otherwise the count was just high.
                if (res.items.isEmpty() && res.LastModifiedVersion != response.LastModifiedVersion) {
                    throw LibraryVersionMisMatchException("library changed while paging: ${response.LastModifiedVersion} -> ${res.LastModifiedVersion}")
                }
                res
            }
            .takeWhile { it.items.isNotEmpty() }
        Observable.just(response).concatWith(rest)
    }


    private fun getCollectionFromIndex(
        groupID: Int,
        modificationSinceVersion: Int,
        index: Int = 0
    ): Observable<ZoteroAPICollectionsResponse> {
        /* Obvservable that gets collections from a certain index. */

        val observable = if (groupID != GroupInfo.NO_GROUP_ID) {
            service.getCollectionsForGroup(modificationSinceVersion, groupID, index)
        } else {
            service.getCollections(modificationSinceVersion, userID, index)
        }

        return observable.map { response: Response<List<CollectionPOJO>> ->
            if (response.code() == 304) {
                throw UpToDateException("304 Collections up to date.")
            } else if (response.code() == 403) {
                throw APIKeyRevokedException("403 Api Key Invalid.")
            }

            val collections = response.body() ?: LinkedList()
            val totalResults = response.headers()["Total-Results"]?.toInt() ?: 0
            val lastModifiedVersion = response.headers()["Last-Modified-Version"]?.toInt() ?: -1
            ZoteroAPICollectionsResponse(collections, totalResults, lastModifiedVersion)
        }
    }

    fun getCollections(
        libraryVersion: Int,
        groupID: Int = GroupInfo.NO_GROUP_ID
    ): Observable<ZoteroAPICollectionsResponse> {
        /* This method provides access to the zotero api collections endpoint.
        *  It allows for both user personal access as well as shared collections (group) access.
        *  You must specify useGroup, as well as provide a groupID*/

        val observable = Observable.create(object : ObservableOnSubscribe<ZoteroAPICollectionsResponse> {
            override fun subscribe(emitter: ObservableEmitter<ZoteroAPICollectionsResponse>) {

                val s = getCollectionFromIndex(
                    groupID,
                    libraryVersion
                )
                val response = s.blockingSingle()
                val total = response.totalResults
                emitter.onNext(response)
                var itemCount = response.collections.size
                while (itemCount < total) {
                    val r = getCollectionFromIndex(
                        groupID,
                        libraryVersion,
                        index = itemCount
                    ).blockingSingle()
                    if (r.collections.isEmpty()) break // see getItems
                    itemCount += r.collections.size
                    emitter.onNext(r)
                }
                emitter.onComplete()
            }
        })
        return observable
    }

    fun getGroupInfo(): Observable<List<GroupInfo>> {
        val groupInfo = service.getGroupInfo(userID)
        return groupInfo.map {
            it.map { groupPojo ->
                GroupInfo(
                    id = groupPojo.groupData.id,
                    version = groupPojo.version,
                    name = groupPojo.groupData.name,
                    description = groupPojo.groupData.description,
                    fileEditing = groupPojo.groupData.fileEditing,
                    libraryEditing = groupPojo.groupData.libraryEditing,
                    libraryReading = groupPojo.groupData.libraryReading,
                    owner = groupPojo.groupData.owner,
                    type = groupPojo.groupData.type,
                    url = groupPojo.groupData.url
                )
            }
        }
    }

    fun getTrashedItemsFromIndex(
        groupID: Int,
        sinceVersion: Int,
        index: Int
    ): Observable<ZoteroAPIItemsResponse> {
        val call = when (groupID) {
            GroupInfo.NO_GROUP_ID -> {
                service.getTrashedItemsForUser(sinceVersion, userID, sinceVersion, index, SYNC_ITEM_TYPE_FILTER)
            }
            else -> {
                service.getTrashedItemsForGroup(sinceVersion, groupID, sinceVersion, index, SYNC_ITEM_TYPE_FILTER)
            }
        }

        return call.map { response ->
            if (response.code() == 304) {
                throw UpToDateException("304 trash already up to date.")
            } else if (response.code() == 200) {
                val s = response.body()?.string() ?: "[]"
                val newItems = ItemJSONConverter().deserialize(s)
                val lastModifiedVersion = response.headers()["Last-Modified-Version"]?.toInt() ?: -1
                val totalResults = response.headers()["Total-Results"]?.toInt() ?: -1

                ZoteroAPIItemsResponse(newItems, lastModifiedVersion, totalResults)
            } else {
                throw Exception("getTrash() Server gave back ${response.code()} message: ${response.body()}")
            }
        }
    }

    fun getTrashedItems(groupID: Int, sinceVersion: Int): Observable<ZoteroAPIItemsResponse> {


        val observable = Observable.create(object : ObservableOnSubscribe<ZoteroAPIItemsResponse> {
            override fun subscribe(emitter: ObservableEmitter<ZoteroAPIItemsResponse>) {
                var itemCount = 0
                val s = getTrashedItemsFromIndex(
                    groupID,
                    sinceVersion,
                    itemCount
                )
                val response = s.blockingSingle()
                val total = response.totalResults
                itemCount += response.items.size
                emitter.onNext(response)
                while (itemCount < total) {
                    val res = getTrashedItemsFromIndex(
                        groupID,
                        sinceVersion,
                        itemCount
                    ).blockingSingle()
                    itemCount += res.items.size
                    if (res.items.size == 0) {
                        throw(RuntimeException("Error, zotero api did not respond with any items for /trash/ $itemCount of ${res.totalResults}"))
                    }
                    emitter.onNext(res)
                }
                emitter.onComplete()
            }
        })

        return observable
    }

    fun uploadAttachmentWithWebdav(
        attachment: Item,
        context: Context,
        expected: RemoteProp?,
        md5: String,
        mtime: Long
    ): Completable {
        return webdav()
            .uploadAttachment(attachment, attachmentStorageManager, expected, md5, mtime)
    }

    /**
     * Uploads to Zotero File Storage. [baseMd5]: the file this device's edit started from
     * (last downloaded or uploaded here; "" if there was none). Zotero replaces the file only
     * if it still has that one, so a newer file from another device is never overwritten.
     * Not the md5 from the last sync: after an upload from here that is stale until the next
     * sync, and the next upload got 412.
     */
    fun updateAttachment(attachment: Item, baseMd5: String): Completable {
        val attachmentUri: Uri = attachmentStorageManager.getAttachmentUri(attachment)
        // Zotero's precondition: the md5 of the file being replaced, or "no file yet"
        val oldMd5 = baseMd5
        val ifMatch = oldMd5.ifEmpty { null }
        val ifNoneMatch = if (oldMd5.isEmpty()) "*" else null

        val newMd5 = attachmentStorageManager.calculateMd5(attachment)
        if (oldMd5 == newMd5) {

            return Completable.error(AlreadyUploadedException("Local attachment version is the same as Zotero's."))
        }
        val mtime = attachmentStorageManager.getMtime(attachment)
        val filename = attachmentStorageManager.getFilenameForItem(attachment)
        val filesize = attachmentStorageManager.getFileSize(attachmentUri)

        val authorizationObservable =
            getUploadAuthorization(attachment, ifMatch, ifNoneMatch, newMd5, filename, filesize, mtime, service)


        val chain = authorizationObservable.map { authorizationPojo ->
            Log.d("zotero", "t ${authorizationPojo.uploadKey}")
            Log.d("zotero", "about to upload ${authorizationPojo.uploadKey}")
            // streamed from the file: reading it into memory first ran out of heap on
            // large PDFs (the Tab S2 has 2-3 GB of RAM)
            val requestBody = attachmentStorageManager.uploadBody(
                attachment, filesize, "application/octet-stream".toMediaType()
            )
            attachmentsService.uploadAttachmentToAmazonMulti(
                authorizationPojo.url,
                authorizationPojo.params.key
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.acl
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.content_MD5
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.success_action_status
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.policy
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.x_amz_algorithm
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.x_amz_credential
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.x_amz_date
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.x_amz_signature
                    .toRequestBody("multipart/form-data".toMediaType()),
                authorizationPojo.params.x_amz_security_token
                    ?.toRequestBody("multipart/form-data".toMediaType()),
                requestBody
            ).flatMap { amazonResponse ->
                if (amazonResponse.code() == 412) {
                    throw PreconditionFailedException("412 Precondition failed when uploading ${attachment.itemKey}")
                }
                if (amazonResponse.code() == 201) {
                    // SUCCESS
                    service.registerUpload(
                        userID,
                        attachment.itemKey,
                        authorizationPojo.uploadKey,
                        ifMatch,
                        ifNoneMatch
                    )
                } else {
                    throw RuntimeException("Amazon Attachment Server Gave server error: ${amazonResponse.code()}")
                }
            }.blockingSingle()
        }

        return Completable.create({ emitter ->
            chain.subscribe(object : Observer<Response<ResponseBody>> {
                override fun onComplete() {
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onNext(zoteroRegisterResponse: Response<ResponseBody>) {
                    when (zoteroRegisterResponse.code()) {
                        204 -> {
                            emitter.onComplete()
                        }
                        412 -> {
                            throw PreconditionFailedException("register upload returned")
                        }
                        413 -> {
                            throw RequestEntityTooLarge("Your file is too large")
                        }
                        else -> {
                            throw Exception("Zotero server replied: ${zoteroRegisterResponse.code()}")
                        }
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "chain subscriber got error ${e}")
                    emitter.onError(e)
                }

            })
        })
    }

    private fun getUploadAuthorization(
        item: Item,
        ifMatch: String?,
        ifNoneMatch: String?,
        newMd5: String,
        filename: String,
        filesize: Long,
        mtime: Long,
        service: ZoteroAPIService
    ): Observable<ZoteroUploadAuthorizationPojo> {
        Log.d("zotero", "upload requested, ${item.itemKey}")
        val observable = service.uploadAttachmentAuthorization(
            userID,
            item.itemKey,
            newMd5,
            filename,
            filesize, //in bytes
            mtime, //this is in milli
            1,
            ifMatch,
            ifNoneMatch
        )
        return observable.map { response ->
            if (response.code() == 200) {
                val jsonString = response.body()!!.string()
                if (jsonString == "{\"exists\":1}") {
                    throw AlreadyUploadedException("File already uploaded")
                } else {
                    Gson().fromJson(
                        jsonString,
                        ZoteroUploadAuthorizationPojo::class.java
                    )
                }
            } else if (response.code() == 412) {
                throw RemoteAttachmentChangedException(
                    "Zotero has a different version of this file than the one you opened " +
                            "(it was changed on another device)."
                )
            } else {
                throw Exception("Zotero server replied: ${response.code()}")
            }
        }
    }
}