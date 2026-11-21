package com.bethanysbakery.api.notification;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** Shared HTTP client factory for outbound provider calls. */
final class ProviderHttp {

    private ProviderHttp() {}

    static RestTemplate timedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }
}
