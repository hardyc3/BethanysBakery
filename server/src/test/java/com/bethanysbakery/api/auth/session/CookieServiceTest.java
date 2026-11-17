package com.bethanysbakery.api.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.support.TestProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

class CookieServiceTest {

    private static final SessionService.SessionTokens TOKENS = new SessionService.SessionTokens(
        UUID.randomUUID(), UUID.randomUUID(), "access", "refresh", 900, Instant.now().plusSeconds(60)
    );

    @Test
    void marksCookiesSecureOutsideLocalAndTestProfiles() {
        CookieService cookies = new CookieService(new MockEnvironment(), TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.setCustomerCookies(response, TOKENS);

        List<String> setCookies = response.getHeaders("Set-Cookie");
        assertThat(setCookies).hasSize(2);
        assertThat(setCookies).allSatisfy(header -> {
            assertThat(header).contains("HttpOnly", "SameSite=Lax", "Secure");
        });
        assertThat(setCookies).anySatisfy(header -> assertThat(header).startsWith("bb_access="));
        assertThat(setCookies).anySatisfy(header -> assertThat(header)
            .startsWith("bb_refresh=").contains("Path=/api/v1/auth"));
    }

    @Test
    void omitsSecureFlagInLocalProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        CookieService cookies = new CookieService(env, TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.setCustomerCookies(response, TOKENS);

        assertThat(response.getHeaders("Set-Cookie")).allSatisfy(header -> assertThat(header).doesNotContain("Secure"));
    }

    @Test
    void csrfCookieIsReadableByJavaScript() {
        CookieService cookies = new CookieService(new MockEnvironment(), TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.setCsrfCookie(response, "csrf-value");

        assertThat(response.getHeader("Set-Cookie"))
            .startsWith("bb_csrf=csrf-value")
            .contains("SameSite=Lax", "Path=/")
            .doesNotContain("HttpOnly");
    }

    @Test
    void clearingCookiesExpiresThemImmediately() {
        CookieService cookies = new CookieService(new MockEnvironment(), TestProperties.defaults());
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.clearCustomerCookies(response);

        assertThat(response.getHeaders("Set-Cookie")).allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
    }
}
