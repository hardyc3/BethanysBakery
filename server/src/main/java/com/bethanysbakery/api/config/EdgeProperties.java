package com.bethanysbakery.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Edge trust settings; S7 enables these only when Cloudflare fronts the origin. */
@ConfigurationProperties(prefix = "bakery.edge")
public record EdgeProperties(boolean cloudflareProxy) {}
