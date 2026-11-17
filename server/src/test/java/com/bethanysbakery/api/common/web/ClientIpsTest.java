package com.bethanysbakery.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpsTest {

    @Test
    void prefersCloudflareConnectingIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.1");
        request.setRemoteAddr("10.0.0.2");

        assertThat(ClientIps.from(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToFirstForwardedAddressThenRemoteAddress() {
        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.addHeader("X-Forwarded-For", "198.51.100.1, 10.0.0.1");
        forwarded.setRemoteAddr("10.0.0.2");
        assertThat(ClientIps.from(forwarded)).isEqualTo("198.51.100.1");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.0.0.2");
        assertThat(ClientIps.from(direct)).isEqualTo("10.0.0.2");
    }

    @Test
    void ignoresEmptyHeaderValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "  ");
        request.setRemoteAddr("10.0.0.2");

        assertThat(ClientIps.from(request)).isEqualTo("10.0.0.2");
    }
}
