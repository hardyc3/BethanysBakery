package com.bethanysbakery.admin.core.network

import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Builds the pairing (no client cert) and post-pairing (mTLS) clients with identical policy.
 * Pure JVM: Android-specific key/trust material is injected so TLS behavior is testable.
 */
class AdminHttpClientFactory(
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) {

    fun create(
        apiUrl: HttpUrl,
        pins: List<String>,
        pinningEnabled: Boolean,
        deviceApiKey: String?,
        keyManagers: Array<KeyManager>? = null,
        trustManager: X509TrustManager? = null,
        appVersion: String,
    ): OkHttpClient {
        require(!pinningEnabled || pins.size >= 2) {
            "Certificate pinning needs a primary and a backup SPKI SHA-256 pin"
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(10.seconds.toJavaDuration())
            .readTimeout(30.seconds.toJavaDuration())
            .writeTimeout(30.seconds.toJavaDuration())
            .addInterceptor { chain ->
                val id = requestId()
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("X-App-Version", appVersion)
                    .header("X-Request-Id", id)
                    .apply { deviceApiKey?.let { header("X-Device-Key", it) } }
                    .build()
                // Never log headers or bodies: they carry the device key and pairing code.
                log("request id=$id ${request.method} ${request.url.scheme}://${request.url.host}${request.url.encodedPath}")
                chain.proceed(request)
            }

        if (keyManagers != null) builder.sslSocketFactory(
            defaultSslSocketFactory(keyManagers, trustManager),
            trustManager ?: defaultTrustManager(),
        )
        if (pinningEnabled) {
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(apiUrl.host, *pins.toTypedArray())
                    .build(),
            )
        }
        return builder.build()
    }
}

private fun defaultSslSocketFactory(
    keyManagers: Array<KeyManager>,
    trustManager: X509TrustManager?,
): javax.net.ssl.SSLSocketFactory {
    val context = javax.net.ssl.SSLContext.getInstance("TLS")
    context.init(keyManagers, trustManager?.let { arrayOf(it) }, null)
    return context.socketFactory
}

private fun defaultTrustManager(): X509TrustManager {
    val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
