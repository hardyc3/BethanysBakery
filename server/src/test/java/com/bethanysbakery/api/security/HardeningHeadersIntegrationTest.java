package com.bethanysbakery.api.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HardeningHeadersIntegrationTest extends IntegrationTestBase {

    @Test
    void prodSecurityHeadersArePresentOnApiResponses() throws Exception {
        mvc.perform(get("/api/v1/config"))
            .andExpect(status().isOk())
            .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Permissions-Policy", containsString("camera=()")));
    }

    @Test
    void oversizedJsonBodyIsRejectedWith413() throws Exception {
        String padding = "x".repeat(1024 * 1024);
        String body = "{\"channel\":\"EMAIL\",\"destination\":\"" + padding + "\"}";
        mvc.perform(post("/api/v1/auth/otp/request")
                .contentType("application/json")
                .content(body.getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }
}
