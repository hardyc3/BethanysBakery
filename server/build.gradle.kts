plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.bethanysbakery"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.8")
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")
    implementation("dev.samstevens.totp:totp:1.7.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    // S3 media pipeline: TwelveMonkeys reads WebP, Sejda writes WebP,
    // metadata-extractor reads EXIF orientation, Thumbnailator resizes.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("net.coobird:thumbnailator:0.4.20")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
