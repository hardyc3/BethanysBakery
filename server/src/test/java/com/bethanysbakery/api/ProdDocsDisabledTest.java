package com.bethanysbakery.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("prod")
class ProdDocsDisabledTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("bakery.name", () -> "Bakery");
        r.add("bakery.timezone", () -> "America/Denver");
        r.add("bakery.pickup-address", () -> "Addr");
        r.add("bakery.maps-url", () -> "https://maps.example.com");
        r.add("bakery.support-email", () -> "a@b.com");
        r.add("bakery.order-cutoff-hours", () -> "12");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void docsUiDisabledInProd() throws Exception {
        // docs endpoint not exposed/permitted in prod security
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().is4xxClientError());
        mvc.perform(get("/v3/api-docs")).andExpect(status().is4xxClientError());
    }
}
