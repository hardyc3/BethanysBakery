package com.bethanysbakery.admin.core.network

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Presents the Keystore-backed private key and the signed client certificate chain.
 * The private key never leaves Android Keystore; only a reference is held here.
 */
class ClientCertificateKeyManager(
    private val privateKey: PrivateKey,
    private val certificateChain: Array<X509Certificate>,
    private val alias: String = "device_key",
) : X509ExtendedKeyManager() {

    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String> =
        if (supports(keyType)) arrayOf(alias) else emptyArray()

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<Principal>?, socket: Socket?): String? =
        if (keyType?.any(::supports) == true) alias else null

    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String> = emptyArray()

    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) certificateChain.copyOf() else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) privateKey else null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = if (keyType?.any(::supports) == true) alias else null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    private fun supports(keyType: String?): Boolean =
        keyType.equals("EC", ignoreCase = true) || keyType.equals("RSA", ignoreCase = true)
}
