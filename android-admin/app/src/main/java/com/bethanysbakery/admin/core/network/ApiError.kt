package com.bethanysbakery.admin.core.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

data class ApiError(
    val code: String,
    val status: Int?,
    val title: String,
    val detail: String? = null,
    val fieldErrors: List<FieldError> = emptyList(),
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
)

sealed interface ApiCallResult<out T> {
    data class Success<T>(val value: T) : ApiCallResult<T>

    data class Failure(val error: ApiError) : ApiCallResult<Nothing>
}

object ApiErrorMapper {

    private const val PINNING_FAILURE = "Certificate pinning failure"

    fun fromHttp(status: Int, problem: ProblemDetails?, requestId: String?, retryAfter: String?): ApiError = ApiError(
        code = problem?.code ?: defaultCode(status),
        status = status,
        title = problem?.title ?: "Request failed ($status)",
        detail = problem?.detail,
        fieldErrors = problem?.errors ?: emptyList(),
        requestId = problem?.requestId ?: requestId,
        retryAfterSeconds = retryAfter?.toLongOrNull(),
    )

    fun fromThrowable(throwable: Throwable): ApiError = when {
        throwable is CancellationException -> ApiError("CANCELLED", null, "Request cancelled")
        throwable.isPinningFailure() -> ApiError(
            code = "TLS_PINNING_FAILED",
            status = null,
            title = "Secure connection failed",
            detail = "The server certificate did not match the pinned keys. Check your network before retrying.",
        )
        throwable is SSLPeerUnverifiedException || throwable is SSLException -> ApiError(
            code = "TLS_ERROR",
            status = null,
            title = "Secure connection failed",
            detail = "The device could not verify the server identity. Do not continue on an untrusted network.",
        )
        throwable is SocketTimeoutException -> ApiError(
            code = "TIMEOUT",
            status = null,
            title = "Request timed out",
            detail = "The server took too long to respond. Check your connection and retry.",
        )
        throwable is IOException -> ApiError(
            code = "NETWORK_UNAVAILABLE",
            status = null,
            title = "Network unavailable",
            detail = "No connection to the bakery server. Check the network and retry.",
        )
        else -> ApiError("UNEXPECTED_ERROR", null, "Something went wrong", throwable.message)
    }

    private fun Throwable.isPinningFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it.message?.contains(PINNING_FAILURE, ignoreCase = true) == true }

    private fun defaultCode(status: Int): String = when (status) {
        400 -> "VALIDATION_ERROR"
        401 -> "UNAUTHORIZED"
        403 -> "FORBIDDEN"
        404 -> "NOT_FOUND"
        409 -> "INVALID_STATE"
        429 -> "RATE_LIMITED"
        in 500..599 -> "INTERNAL_ERROR"
        else -> "HTTP_ERROR"
    }
}
