package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.auth.session.CsrfService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    private final CsrfService csrfService;

    public CsrfController(CsrfService csrfService) {
        this.csrfService = csrfService;
    }

    @GetMapping("/api/v1/auth/csrf")
    public AuthApi.CsrfToken csrf(HttpServletResponse response) {
        return new AuthApi.CsrfToken(csrfService.issue(response));
    }
}
