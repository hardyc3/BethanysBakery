package com.bethanysbakery.admin.feature.pairing

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import com.bethanysbakery.admin.core.network.AdminHttpClientFactory
import com.bethanysbakery.admin.core.network.asApiBaseUrl
import com.bethanysbakery.admin.core.network.ApiCallResult
import com.bethanysbakery.admin.core.network.ApiError
import com.bethanysbakery.admin.core.network.ApiErrorMapper
import com.bethanysbakery.admin.core.network.PairDeviceRequest
import com.bethanysbakery.admin.core.network.PairDeviceResponse
import com.bethanysbakery.admin.core.network.ProblemDetails
import com.bethanysbakery.admin.core.security.KeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface PairApi {
    @POST
    suspend fun pair(@Url url: String, @Body request: PairDeviceRequest): Response<PairDeviceResponse>
}

interface PairingRepository {
    suspend fun pair(input: PairingInput, keyMaterial: KeyMaterial, deviceName: String): ApiCallResult<PairDeviceResponse>
}

class RetrofitPairingRepository(
    private val config: AdminBuildConfig,
    private val clientFactory: AdminHttpClientFactory,
    private val json: Json,
) : PairingRepository {

    override suspend fun pair(
        input: PairingInput,
        keyMaterial: KeyMaterial,
        deviceName: String,
    ): ApiCallResult<PairDeviceResponse> = withContext(Dispatchers.IO) {
        val client = clientFactory.create(
            apiUrl = input.apiUrl,
            pins = config.pins,
            pinningEnabled = config.pinningEnabled,
            deviceApiKey = null,
            appVersion = config.appVersion,
        )
        val api = Retrofit.Builder()
            .baseUrl(input.apiUrl.asApiBaseUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PairApi::class.java)

        try {
            val response = api.pair(
                url = input.pairUrl.toString(),
                request = PairDeviceRequest(
                    pairingCode = input.code,
                    deviceName = deviceName,
                    publicKeyPem = keyMaterial.publicKeyPem,
                    csrPem = keyMaterial.csrPem,
                    appVersion = config.appVersion,
                ),
            )
            val body = response.body()
            when {
                response.isSuccessful && body != null -> ApiCallResult.Success(body)
                response.isSuccessful -> ApiCallResult.Failure(
                    ApiError("EMPTY_RESPONSE", response.code(), "Empty response", "The server returned no pairing result."),
                )
                else -> ApiCallResult.Failure(mapError(response))
            }
        } catch (throwable: Throwable) {
            ApiCallResult.Failure(ApiErrorMapper.fromThrowable(throwable))
        }
    }

    private fun mapError(response: Response<PairDeviceResponse>): ApiError {
        val problem = runCatching {
            response.errorBody()?.string()?.let { json.decodeFromString<ProblemDetails>(it) }
        }.getOrNull()
        return ApiErrorMapper.fromHttp(
            status = response.code(),
            problem = problem,
            requestId = response.headers()["X-Request-Id"],
            retryAfter = response.headers()["Retry-After"],
        )
    }
}
