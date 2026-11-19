package com.bethanysbakery.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "bakery.edge.cloudflare-proxy=true")
class EdgeSecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void missingOrForeignCountryIsBlocked() throws Exception {
        mvc.perform(get("/api/v1/config"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("COUNTRY_BLOCKED"));
        mvc.perform(get("/api/v1/config").header("CF-IPCountry", "CA"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("COUNTRY_BLOCKED"));
    }

    @Test
    void usCountryPassesAndProviderWebhookIsExempt() throws Exception {
        mvc.perform(get("/api/v1/config").header("CF-IPCountry", "US"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/webhooks/textbee").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void foreignMutationOriginIsBlockedWithoutCorsHeaders() throws Exception {
        mvc.perform(get("/api/v1/config").header("CF-IPCountry", "US")
                .header("Origin", "https://evil.example"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void configuredOriginIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/config").header("CF-IPCountry", "US")
                .header("Origin", "https://public.example.com"))
            .andExpect(status().isOk());
    }

    @Test
    void adminHostIsExemptFromCountryCheck() throws Exception {
        mvc.perform(get("/admin/v1/app-info").header("X-Device-Key", "unknown"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
