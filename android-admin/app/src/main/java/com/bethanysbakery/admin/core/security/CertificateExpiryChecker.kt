package com.bethanysbakery.admin.core.security

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.days

enum class CertificateStatus { VALID, EXPIRING_SOON, EXPIRED }

object CertificateExpiryChecker {
    val warningWindow = 14.days

    fun status(expiresAt: Instant, now: Instant = Instant.now()): CertificateStatus = when {
        !expiresAt.isAfter(now) -> CertificateStatus.EXPIRED
        expiresAt <= now.plus(warningWindow.inWholeDays, ChronoUnit.DAYS) -> CertificateStatus.EXPIRING_SOON
        else -> CertificateStatus.VALID
    }
}
