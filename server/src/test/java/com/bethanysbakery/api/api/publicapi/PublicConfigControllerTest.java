package com.bethanysbakery.api.api.publicapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublicConfigControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("bakery.name", () -> "Bethany's Bakery");
        r.add("bakery.timezone", () -> "America/Denver");
        r.add("bakery.pickup-address", () -> "Lehi, Utah");
        r.add("bakery.maps-url", () -> "https://maps.google.com/?q=Lehi");
        r.add("bakery.support-email", () -> "hi@example.com");
        r.add("bakery.order-cutoff-hours", () -> "12");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void returnsConfigAndRequestId() throws Exception {
        mvc.perform(get("/api/v1/config"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.bakeryName").value("Bethany's Bakery"))
            .andExpect(jsonPath("$.timezone").value("America/Denver"))
            .andExpect(jsonPath("$.pickupAddress").value("Lehi, Utah"))
            .andExpect(jsonPath("$.mapsUrl").value("https://maps.google.com/?q=Lehi"))
            .andExpect(jsonPath("$.supportEmail").value("hi@example.com"))
            .andExpect(jsonPath("$.orderCutoffHours").value(12));
    }
}
