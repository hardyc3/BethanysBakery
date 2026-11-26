package com.bethanysbakery.admin.core.network

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import com.bethanysbakery.admin.core.network.asApiBaseUrl
import com.bethanysbakery.admin.feature.pairing.PairingRepository
import com.bethanysbakery.admin.feature.pairing.PairingUrlValidator
import com.bethanysbakery.admin.feature.pairing.RetrofitPairingRepository
import com.bethanysbakery.admin.feature.pairing.UnpairUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideHttpClientFactory(): AdminHttpClientFactory = AdminHttpClientFactory()

    @Provides
    @Singleton
    fun providePairingRepository(
        config: AdminBuildConfig,
        factory: AdminHttpClientFactory,
        json: Json,
    ): PairingRepository = RetrofitPairingRepository(config, factory, json)
}

/** Retrofit client for post-pairing A2+ calls: mTLS, device key header, and pinning. */
@Singleton
class AuthenticatedApiProvider @Inject constructor(
    private val config: AdminBuildConfig,
    private val factory: AdminHttpClientFactory,
    private val trust: TrustedCaProvider,
    private val keys: com.bethanysbakery.admin.core.security.DeviceKeyManager,
    private val store: com.bethanysbakery.admin.core.storage.PairedDeviceStore,
    private val deviceKey: DeviceApiKeyProvider,
    private val unpair: UnpairUseCase,
    private val json: Json,
) {
    suspend fun adminApi(): AdminApi {
        val paired = store.load() ?: error("Device is not paired")
        if (!PairingUrlValidator.isValid(paired.apiUrl, PairingUrlValidator.Endpoint.API, config)) {
            unpair()
            error("Stored API URL is not allowed for this build")
        }
        deviceKey.set(paired.apiKey)
        val client = factory.create(
            apiUrl = paired.apiUrl.toHttpUrl(),
            pins = config.pins,
            pinningEnabled = config.pinningEnabled,
            deviceApiKey = paired.apiKey,
            keyManagers = arrayOf(ClientCertificateKeyManager(keys.privateKey(), keys.certificateChain())),
            trustManager = trust.trustManager(),
            appVersion = config.appVersion,
        )
        return Retrofit.Builder()
            .baseUrl(paired.apiUrl.toHttpUrl().asApiBaseUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)
    }
}
