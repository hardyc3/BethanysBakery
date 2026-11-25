package com.bethanysbakery.admin.core.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class CertificateExpiryCheckerTest {

    private val now = Instant.parse("2026-11-25T12:00:00Z")

    @Test
    fun `valid certificate has more than fourteen days remaining`() {
        val expiry = now.plus(15, ChronoUnit.DAYS)

        assertEquals(CertificateStatus.VALID, CertificateExpiryChecker.status(expiry, now))
    }

    @Test
    fun `certificate expiring in fourteen days warns`() {
        val expiry = now.plus(14, ChronoUnit.DAYS)

        assertEquals(CertificateStatus.EXPIRING_SOON, CertificateExpiryChecker.status(expiry, now))
    }

    @Test
    fun `expired certificate forces re-pairing`() {
        assertEquals(CertificateStatus.EXPIRED, CertificateExpiryChecker.status(now.minusSeconds(1), now))
        assertEquals(CertificateStatus.EXPIRED, CertificateExpiryChecker.status(now, now))
    }
}
