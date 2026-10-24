package com.mickstarify.zooforzotero.ZoteroAPI

import android.content.Context
import android.util.Log
import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.WebdavAuthMode
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import net.lingala.zip4j.ZipFile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Webdav(
    preferenceManager: PreferenceManager
) {
    private var client: OkHttpClient
    private var uploader: WebdavUploader
    private val backupBeforeReplace = preferenceManager.isWebDAVBackupBeforeUploadEnabled()
    var address: String


    /** Whether the folder at [address] answers a PROPFIND (as Zotero's own check does). */
    fun testConnection(): Boolean {
        val request = Request.Builder().url(address)
            .header("Depth", "0")
            .method("PROPFIND", null)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) {
                throw IOException("The server answered ${response.code} ${response.message}")
            }
            return true
        }
    }

    /** The body of [url]; ZoteroNotFoundException on 404, IOException on other errors. */
    private fun get(url: String): ResponseBody {
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            if (response.code == 404) throw ZoteroNotFoundException("$url not found on the WebDAV server")
            throw IOException("WebDAV server answered ${response.code} for $url")
        }
        return response.body ?: throw IOException("empty response for $url")
    }

    fun downloadPropToString(webpath: String): String = get(webpath).use { it.string() }

    /**
     * Downloads KEY.zip, extracts the attachment from it and writes it to storage. The
     * zip holds one file, Zotero's; only that entry is extracted, under a fixed name,
     * so an entry name can't point outside the temporary folder.
     */
    fun downloadFileRx(
        attachment: Item,
        context: Context,
        attachmentStorageManager: AttachmentStorageManager
    ): Observable<DownloadProgress> {
        val key = attachment.itemKey.uppercase()
        val webpathProp = "$address/$key.prop"
        val webpathZip = "$address/$key.zip"

        val observable: Observable<DownloadProgress> = Observable.create { emitter ->
            val zipFile = attachmentStorageManager.createTempFile("$key.zip")
            val extracted = attachmentStorageManager.createTempFile("$key.download")
            try {
                val prop = WebdavProp(downloadPropToString(webpathProp))
                val reporter = ProgressThrottle()
                var total = 0L
                get(webpathZip).byteStream().use { input ->
                    zipFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var read = input.read(buffer)
                        while (read > 0 && !emitter.isDisposed) { // stops when cancelled
                            output.write(buffer, 0, read)
                            total += read
                            if (reporter.due()) {
                                emitter.onNext(DownloadProgress(total, -1, prop.mtime, prop.hash))
                            }
                            read = input.read(buffer)
                        }
                    }
                }
                // cancelled: the zip is partial, and the copy on the device stays as it was
                if (emitter.isDisposed) return@create
                emitter.onNext(DownloadProgress(total, -1, prop.mtime, prop.hash))
                val zip = ZipFile(zipFile)
                val entry = zip.fileHeaders.firstOrNull { !it.isDirectory }
                    ?: throw IOException("$key.zip is empty")
                zip.extractFile(entry, extracted.parent, extracted.name)
                attachmentStorageManager.writeAttachmentFromFile(extracted, attachment)
                emitter.onComplete()
            } catch (e: Exception) {
                Log.e("zotero", "WebDAV download of $key failed: $e (disposed: ${emitter.isDisposed})")
                // after dispose() there's no one to tell; throwing then would be undeliverable
                if (!emitter.isDisposed) {
                    throw e
                }
            } finally {
                zipFile.delete()
                extracted.delete()
            }
        }
        return observable
    }

    /**
     * Zips the attachment and replaces it on the server via [WebdavUploader].
     * [expected] is the .prop read for the pre-upload check; [md5]/[mtime] go into
     * the new .prop and must be what the caller then patches onto the Zotero item.
     */
    fun uploadAttachment(
        attachment: Item,
        attachmentStorageManager: AttachmentStorageManager,
        expected: RemoteProp?,
        md5: String,
        mtime: Long
    ): Completable {
        return Completable.fromAction {
            // zip the file under its real name, as Zotero does
            val fileInputStream =
                attachmentStorageManager.getItemInputStream(attachment).source()
            val filename = attachmentStorageManager.getFilenameForItem(attachment)
            val tempFile = attachmentStorageManager.createTempFile(filename)
            val sinkBuffer = tempFile.outputStream().sink().buffer()
            sinkBuffer.writeAll(fileInputStream)
            sinkBuffer.close()
            fileInputStream.close()

            val zipFile =
                attachmentStorageManager.createTempFile("${attachment.itemKey.uppercase()}_NEW.zip")
            ZipFile(zipFile).addFile(tempFile)
            tempFile.delete()

            try {
                if (backupBeforeReplace) {
                    uploader.backupCurrent(attachment.itemKey)
                }
                uploader.replace(
                    attachment.itemKey, zipFile, WebdavProp(mtime, md5).serialize(), expected
                )
            } finally {
                zipFile.delete()
            }
        }
    }

    /** The attachment's .prop on the server (with its ETag), or null if none. */
    fun getRemoteProp(attachment: Item): RemoteProp? = uploader.readProp(attachment.itemKey)

    val authCach: MutableMap<String, CachingAuthenticator> = HashMap()

    init {
        val username = preferenceManager.getWebDAVUsername()
        val password = preferenceManager.getWebDAVPassword()
        val verifySSL = preferenceManager.getVerifySSLForWebDAV()


        val clientBuilder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
            .callTimeout(0, TimeUnit.SECONDS) // no call timeout.
            .connectTimeout(preferenceManager.getWebDAVConnectTimeout(), TimeUnit.MILLISECONDS)
            .readTimeout(preferenceManager.getWebDAVReadTimeout(), TimeUnit.MILLISECONDS)
            .writeTimeout(preferenceManager.getWebDAVWriteTimeout(), TimeUnit.MILLISECONDS);


        if (username != "" && password != "") {
            val credentials = Credentials(username, password)
            val digestAuthenticator = DigestAuthenticator(credentials)
            val basicAuthenticator = BasicAuthenticator(credentials)

            val authenticator = when (preferenceManager.getWebDAVAuthMode()) {
                WebdavAuthMode.BASIC -> basicAuthenticator
                WebdavAuthMode.DIGEST -> digestAuthenticator
                WebdavAuthMode.AUTOMATIC -> {
                    DispatchingAuthenticator.Builder()
                        .with("digest", digestAuthenticator)
                        .with("basic", basicAuthenticator)
                        .build()
                }
            }

            clientBuilder
                .authenticator(CachingAuthenticatorDecorator(authenticator, authCach))
                .addInterceptor(AuthenticationCacheInterceptor(authCach))
        }

        if (!verifySSL) {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    @Throws(CertificateException::class)
                    override fun checkClientTrusted(
                        chain: Array<X509Certificate>,
                        authType: String
                    ) {
                    }

                    @Throws(CertificateException::class)
                    override fun checkServerTrusted(
                        chain: Array<X509Certificate>,
                        authType: String
                    ) {
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> {
                        return arrayOf()
                    }
                }
            )
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory
            clientBuilder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            clientBuilder.hostnameVerifier(HostnameVerifier { hostname, session -> true })
        }

        client = clientBuilder.build()
        uploader = WebdavUploader(client, preferenceManager.getWebDAVAddress())

        this.address = preferenceManager.getWebDAVAddress()
    }
}


