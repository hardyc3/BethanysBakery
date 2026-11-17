package com.bethanysbakery.api.auth.session;

import com.bethanysbakery.api.auth.crypto.CryptoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

@Service
public class CsrfService {
    private final CryptoService crypto;
    private final CookieService cookies;

    public CsrfService(CryptoService crypto, CookieService cookies) {
        this.crypto = crypto;
        this.cookies = cookies;
    }

    public String issue(HttpServletResponse response) {
        String token = crypto.randomToken(32);
        cookies.setCsrfCookie(response, token);
        return token;
    }

    public boolean validate(HttpServletRequest request) {
        String cookie = cookies.read(request, CookieService.CSRF_COOKIE);
        String header = request.getHeader(CookieService.CSRF_HEADER);
        return crypto.constantTimeEquals(cookie, header);
    }
}
