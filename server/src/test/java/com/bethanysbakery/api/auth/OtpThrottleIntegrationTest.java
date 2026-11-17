package com.bethanysbakery.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
    "bakery.auth.otp-resend-seconds=0",
    "bakery.auth.otp-max-sends-per-15m=1"
})
class OtpThrottleIntegrationTest extends IntegrationTestBase {

    @Test
    void rejectsMoreThanTheConfiguredSendLimitWithRetryAfter() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("channel", "EMAIL", "destination", "throttle@example.com"));

        mvc.perform(post("/api/v1/auth/otp/request")
                .header("CF-Connecting-IP", "10.2.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/auth/otp/request")
                .header("CF-Connecting-IP", "10.2.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
