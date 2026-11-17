package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.config.BakeryProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAppInfoController {
    private final BakeryProperties.AdminApp config;

    public AdminAppInfoController(BakeryProperties properties) {
        this.config = properties.adminApp();
    }

    @GetMapping("/admin/v1/app-info")
    public AuthApi.AdminAppInfo info() {
        return new AuthApi.AdminAppInfo(
            config.latestVersion(), config.minVersion(), config.downloadUrl(), config.releaseNotes()
        );
    }
}
