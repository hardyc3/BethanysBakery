package com.bethanysbakery.admin.core.network

import com.bethanysbakery.admin.core.config.AdminBuildConfig
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** System CAs plus an optional debug-only CA for local mTLS servers. */
@Singleton
class TrustedCaProvider @Inject constructor(private val config: AdminBuildConfig) {
    fun trustManager(): X509TrustManager? {
        if (!config.isDebug || config.testCaPem.isBlank()) return null
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(config.testCaPem.toByteArray(Charsets.UTF_8)))
        certificates.forEachIndexed { index, certificate ->
            keyStore.setCertificateEntry("admin_test_ca_$index", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
