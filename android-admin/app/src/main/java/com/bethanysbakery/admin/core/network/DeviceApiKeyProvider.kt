package com.bethanysbakery.admin.core.network

import javax.inject.Inject
import javax.inject.Singleton

/** In-memory mirror for the synchronous OkHttp interceptor; encrypted source of truth is storage. */
@Singleton
class DeviceApiKeyProvider @Inject constructor() {
    @Volatile
    var apiKey: String? = null
        private set

    fun set(value: String) {
        apiKey = value
    }

    fun clear() {
        apiKey = null
    }
}
