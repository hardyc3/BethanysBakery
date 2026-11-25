# kotlinx.serialization keeps its own metadata; keep model serializers used by Retrofit.
-keep class kotlinx.serialization.json.** { *; }
-keepattributes *Annotation*, Signature, Exceptions

# BouncyCastle provider is loaded reflectively for CSR signing.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.openssl.jcajce.JcaPEMWriter { *; }
