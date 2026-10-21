package com.mickstarify.zooforzotero.SyncSetup.domain

import com.mickstarify.zooforzotero.ZoteroAPI.Model.KeyInfo
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPIService
import com.mickstarify.zooforzotero.common.DispatchersProvider
import com.mickstarify.zooforzotero.di.UnauthenticatedApiService
import kotlinx.coroutines.withContext
import retrofit2.Response
import javax.inject.Inject

interface ApiKeyRepository {

    sealed class ValidationResult {
        data class Success(val keyInfo: KeyInfo) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
        object NetworkError : ValidationResult()
    }

    suspend fun validateApiKey(apiKey: String): ValidationResult
}

class ApiKeyRepositoryImpl @Inject constructor(
    private val dispatchersProvider: DispatchersProvider,
    @UnauthenticatedApiService private val apiService: ZoteroAPIService
) : ApiKeyRepository {

    override suspend fun validateApiKey(apiKey: String): ApiKeyRepository.ValidationResult =
        withContext(dispatchersProvider.io) {
            try {
                val response: Response<KeyInfo> = apiService.getKeyInfo(apiKey).execute()

                when (response.code()) {
                    200 -> {
                        val keyInfo = response.body()
                        if (keyInfo == null) {
                            ApiKeyRepository.ValidationResult.Error("Zotero sent an answer the app doesn't understand. Try again later.")
                        } else {
                            ApiKeyRepository.ValidationResult.Success(keyInfo)
                        }
                    }

                    404 -> {
                        ApiKeyRepository.ValidationResult.Error("Zotero doesn't know this key. Check it and try again.")
                    }

                    else -> {
                        ApiKeyRepository.ValidationResult.Error("Zotero answered with an error (HTTP ${response.code()}). Check the key, or try again later.")
                    }
                }
            } catch (_: Exception) {
                ApiKeyRepository.ValidationResult.NetworkError
            }
        }
}