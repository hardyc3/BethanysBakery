package com.bethanysbakery.admin.core.network

import okhttp3.HttpUrl

/** Retrofit base URLs must end in '/'; configured admin API URLs intentionally do not. */
internal fun HttpUrl.asApiBaseUrl(): HttpUrl =
    if (encodedPath.endsWith("/")) this else newBuilder().addPathSegment("").build()
