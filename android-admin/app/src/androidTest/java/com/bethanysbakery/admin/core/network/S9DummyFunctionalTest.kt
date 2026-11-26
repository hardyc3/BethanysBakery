package com.bethanysbakery.admin.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Runs only from scripts/functional-test.sh, against the loopback S9 dummy image. */
@RunWith(AndroidJUnit4::class)
class S9DummyFunctionalTest {

    @Test
    fun dummyDeviceKeyAndAdminTokenReadPairedDevices() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val apiUrl = args.getString(ARG_API_URL)
        val deviceKey = args.getString(ARG_DEVICE_KEY)
        val adminToken = args.getString(ARG_ADMIN_TOKEN)
        assumeTrue(
            "Run android-admin/scripts/functional-test.sh with the S9 dummy image",
            apiUrl != null && deviceKey != null && adminToken != null,
        )

        val baseUrl = checkNotNull(apiUrl).toHttpUrl()
        val client = AdminHttpClientFactory(requestId = { "s9-functional-test" })
            .create(
                apiUrl = baseUrl,
                pins = emptyList(),
                pinningEnabled = false,
                deviceApiKey = checkNotNull(deviceKey),
                appVersion = "1.0.0",
            )
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${checkNotNull(adminToken)}")
                    .build()
                chain.proceed(request)
            }
            .build()
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)

        val devices = api.devices()

        assertTrue("Dummy image must have the fixed paired device", devices.isNotEmpty())
        assertTrue(devices.any { it.platform == "ANDROID" })
    }

    private companion object {
        const val ARG_API_URL = "apiUrl"
        const val ARG_DEVICE_KEY = "deviceKey"
        const val ARG_ADMIN_TOKEN = "adminToken"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
