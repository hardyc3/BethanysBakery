package com.bethanysbakery.api.auth.session;

import com.bethanysbakery.api.config.BakeryProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class CookieService {
    public static final String ACCESS_COOKIE = "bb_access";
    public static final String REFRESH_COOKIE = "bb_refresh";
    public static final String CSRF_COOKIE = "bb_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final boolean secure;
    private final BakeryProperties.Auth config;

    public CookieService(Environment env, BakeryProperties properties) {
        this.secure = !env.acceptsProfiles(Profiles.of("local", "test"));
        this.config = properties.auth();
    }

    public void setCustomerCookies(HttpServletResponse response, SessionService.SessionTokens tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, tokens.accessToken(), true, "/",
            config.customerAccessTtl()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, tokens.refreshToken(), true,
            "/api/v1/auth", config.customerRefreshTtl()).toString());
    }

    public void setCsrfCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(CSRF_COOKIE, token, false, "/",
            config.customerRefreshTtl()).toString());
    }

    public void clearCustomerCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, "", true, "/", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", true, "/api/v1/auth", Duration.ZERO).toString());
    }

    public String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private ResponseCookie cookie(String name, String value, boolean httpOnly, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(secure)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build();
    }
}
