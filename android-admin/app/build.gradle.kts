import java.net.URI
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val pairUrl = providers.gradleProperty("APP_PAIR_URL").get()
val adminApiUrl = providers.gradleProperty("APP_ADMIN_API_URL").get()
val pins = (
    providers.gradleProperty("ADMIN_API_PINS").orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv("ADMIN_API_PINS").orEmpty()
    )
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
val debugLanHosts = providers.gradleProperty("DEBUG_LAN_HOSTS").get()
val debugPinning = providers.gradleProperty("ADMIN_API_PINNING_DEBUG").get().toBoolean()
val testCaPem = providers.gradleProperty("ADMIN_TEST_CA_PEM").get()

fun configString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val pinPattern = Regex("^sha256/[A-Za-z0-9+/]{43}=$")

fun requestsReleaseBuild(): Boolean {
    val requested = gradle.startParameter.taskNames.map { it.lowercase(Locale.ROOT) }
    return requested.any {
        it.contains("release") || it in setOf("assemble", "build", "bundle", "publish")
    }
}

fun validateReleaseConfig() {
    if (pins.size < 2) {
        error(
            "ADMIN_API_PINS needs at least one primary and one backup SPKI SHA-256 pin for release. " +
                "Pass -PADMIN_API_PINS=pin1,pin2 or set the ADMIN_API_PINS environment variable."
        )
    }
    val invalid = pins.firstOrNull { !pinPattern.matches(it) }
    require(invalid == null) { "Invalid ADMIN_API_PINS entry: $invalid. Use sha256/<base64> SPKI pins." }

    val pair = URI(pairUrl)
    val api = URI(adminApiUrl)
    val validReleaseUrl = { uri: URI ->
        uri.scheme == "https" && uri.port == -1 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && !uri.host.isNullOrBlank()
    }
    require(validReleaseUrl(pair) && validReleaseUrl(api)) {
        "Release URLs must be HTTPS on default ports with no userinfo, query, or fragment."
    }
    require(pair.host == api.host) {
        "Release APP_PAIR_URL and APP_ADMIN_API_URL must share one pinned host."
    }
    require(pair.path == "/admin/v1/devices/pair" && api.path == "/admin/v1") {
        "Release URL paths must be /admin/v1/devices/pair and /admin/v1."
    }
}

android {
    namespace = "com.bethanysbakery.admin"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.bethanysbakery.admin"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "APP_PAIR_URL", configString(pairUrl))
        buildConfigField("String", "APP_ADMIN_API_URL", configString(adminApiUrl))
        buildConfigField("String", "ADMIN_API_PINS", configString(pins.joinToString(",")))
        buildConfigField("String", "DEBUG_LAN_HOSTS", configString(debugLanHosts))
        buildConfigField("String", "TEST_CA_PEM", configString(testCaPem))
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("Boolean", "PINNING_ENABLED", debugPinning.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("Boolean", "PINNING_ENABLED", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle ships the same licence file in three jars.
            excludes += setOf("META-INF/LICENSE.md")
        }
    }

    testOptions {
        unitTests.all { test ->
            test.useJUnitPlatform {
                if (providers.gradleProperty("includeDockerIntegration").orNull?.toBoolean() == true) {
                    includeTags("docker-integration")
                } else {
                    excludeTags("docker-integration")
                }
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.camera:camera-camera2:1.7.0")
    implementation("androidx.camera:camera-lifecycle:1.7.0")
    implementation("androidx.camera:camera-view:1.7.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit4:5.5.1")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
}

// Variant-scoped validation cannot be bypassed by `assemble` or IDE builds.
androidComponents {
    onVariants { variant ->
        if (variant.name == "release" && requestsReleaseBuild()) validateReleaseConfig()
    }
}
