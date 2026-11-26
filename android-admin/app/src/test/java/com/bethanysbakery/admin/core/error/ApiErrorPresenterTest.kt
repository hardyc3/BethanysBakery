package com.bethanysbakery.admin.core.error

import com.bethanysbakery.admin.core.network.ApiError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiErrorPresenterTest {

    @Test
    fun `invalid code gives actionable pairing message`() {
        val error = ApiError("UNAUTHORIZED", 401, "Unauthorized", "Invalid pairing code")

        val message = ApiErrorPresenter.message(error)

        assertEquals("That pairing code is invalid or expired. Ask for a new QR code.", message)
    }

    @Test
    fun `rate limit includes retry delay when server supplies it`() {
        val error = ApiError("RATE_LIMITED", 429, "Too Many Requests", retryAfterSeconds = 60)

        assertEquals("Too many attempts. Try again in 60 seconds.", ApiErrorPresenter.message(error))
    }

    @Test
    fun `pinning failure warns against untrusted networks`() {
        val error = ApiError("TLS_PINNING_FAILED", null, "Secure connection failed")

        val message = ApiErrorPresenter.message(error)

        assertTrue(message.contains("not trusted"))
    }

    @Test
    fun `unknown error never exposes raw server detail`() {
        val error = ApiError("SERVER_ERROR", 500, "Server error", "jdbc://internal-db failed")

        assertEquals("Pairing failed. Retry or enter the code manually.", ApiErrorPresenter.message(error))
        assertTrue(!ApiErrorPresenter.message(error).contains("internal-db"))
    }

    @Test
    fun `local transport and state errors have actionable copy`() {
        val invalidState = ApiErrorPresenter.message(ApiError("INVALID_STATE", null, "Invalid state", "raw"))
        val tls = ApiErrorPresenter.message(ApiError("TLS_ERROR", null, "TLS error", "raw"))
        val timeout = ApiErrorPresenter.message(ApiError("TIMEOUT", null, "Timeout", "raw"))
        val offline = ApiErrorPresenter.message(ApiError("NETWORK_UNAVAILABLE", null, "Offline", "raw"))
        val empty = ApiErrorPresenter.message(ApiError("EMPTY_RESPONSE", null, "Empty", "raw"))
        val noRetryAfter = ApiErrorPresenter.message(ApiError("RATE_LIMITED", 429, "Rate limited"))

        assertTrue(invalidState.contains("already paired"))
        assertTrue(tls.contains("untrusted network"))
        assertTrue(timeout.contains("too long"))
        assertTrue(offline.contains("No network"))
        assertTrue(empty.contains("empty response"))
        assertTrue(noRetryAfter.contains("Wait a moment"))
        listOf(invalidState, tls, timeout, offline, empty, noRetryAfter).forEach {
            assertTrue(!it.contains("raw"))
        }
    }
}
