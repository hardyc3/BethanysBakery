package com.bethanysbakery.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.device.AdminDeviceService;
import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.support.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** S8 isolation: customers never reach admin controllers; only paired admin devices do. */
class AdminAccessIntegrationTest extends IntegrationTestBase {

    @Autowired
    SessionService sessions;
    @Autowired
    AdminDeviceService adminDevices;

    private static Stream<Arguments> adminEndpoints() {
        return Stream.of(
            Arguments.of("GET", "/admin/v1/orders"),
            Arguments.of("GET", "/admin/v1/orders/" + UUID.randomUUID()),
            Arguments.of("GET", "/admin/v1/summary"),
            Arguments.of("GET", "/admin/v1/bake-list"),
            Arguments.of("GET", "/admin/v1/devices"),
            Arguments.of("GET", "/admin/v1/notifications"),
            Arguments.of("POST", "/admin/v1/notifications/" + UUID.randomUUID() + "/retry"),
            Arguments.of("GET", "/admin/v1/audit-events"),
            Arguments.of("GET", "/admin/v1/app-info"),
            Arguments.of("GET", "/admin/v1/menus"),
            Arguments.of("POST", "/admin/v1/menus/" + UUID.randomUUID() + "/publish"),
            Arguments.of("GET", "/admin/v1/menu-dates/" + UUID.randomUUID()),
            Arguments.of("GET", "/admin/v1/menu-dates/" + UUID.randomUUID() + "/offerings"),
            Arguments.of("GET", "/admin/v1/menu-dates/" + UUID.randomUUID() + "/pickup-windows"),
            Arguments.of("GET", "/admin/v1/item-templates"),
            Arguments.of("GET", "/admin/v1/offerings/" + UUID.randomUUID()),
            Arguments.of("GET", "/admin/v1/pickup-windows/" + UUID.randomUUID()),
            Arguments.of("GET", "/admin/v1/me"),
            Arguments.of("DELETE", "/admin/v1/media/" + UUID.randomUUID()),
            Arguments.of("POST", "/admin/v1/auth/login"),
            Arguments.of("POST", "/admin/v1/auth/refresh"),
            Arguments.of("POST", "/admin/v1/auth/logout")
        );
    }

    @ParameterizedTest
    @MethodSource("adminEndpoints")
    void authenticatedCustomerIsForbiddenBeforeController(String method, String path) throws Exception {
        Cookie cookie = customerCookie("customer-" + UUID.randomUUID() + "@example.com");

        mvc.perform(request(HttpMethod.valueOf(method), path).cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @MethodSource("adminEndpoints")
    void unpairedDeviceIsUnauthorized(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void revokedDeviceIsUnauthorizedOnAdminEndpoints() throws Exception {
        var admin = seedAdmin("access-admin@example.com", "correct horse battery staple", List.of());
        var device = seedDevice(admin.id(), "access-device-key", "Revoked");
        adminDevices.revoke(device.id(), admin.id());

        mvc.perform(get("/admin/v1/orders").header("X-Device-Key", device.apiKey()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void pairEndpointIsExemptAtOriginAndRateLimited() throws Exception {
        seedAdmin("pair-exempt@example.com", "correct horse battery staple", List.of());
        String body = """
            {"pairingCode":"not-a-code","deviceName":"Pair Probe",
             "publicKeyPem":"public-key","csrPem":"csr"}
            """;
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/admin/v1/devices/pair")
                    .header("CF-Connecting-IP", "10.7.0.1")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        }
        MvcResult limited = mvc.perform(post("/admin/v1/devices/pair")
                .header("CF-Connecting-IP", "10.7.0.1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andReturn();
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    private Cookie customerCookie(String email) {
        Customer customer = new Customer(Uuids.v7(), "Test Customer", email, "+15555550109",
            CustomerStatus.ACTIVE, Instant.now());
        customers.save(customer);
        return new Cookie("bb_access", sessions.create(SubjectType.CUSTOMER, customer.getId(), null,
            "test", "10.0.0.9").accessToken());
    }

}
