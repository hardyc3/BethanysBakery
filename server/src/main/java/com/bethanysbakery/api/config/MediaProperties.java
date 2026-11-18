package com.bethanysbakery.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bakery.media")
public record MediaProperties(
    @NotBlank(message = "MEDIA_ROOT must not be blank") String root,
    @Min(value = 1, message = "MEDIA_MAX_UPLOAD_BYTES must be positive")
    @Max(value = 10485760, message = "MEDIA_MAX_UPLOAD_BYTES must not exceed 10485760") long maxUploadBytes,
    @Min(value = 1, message = "MEDIA_MAX_EDGE_PIXELS must be positive") int maxEdgePixels,
    @Min(value = 1, message = "MEDIA_MAX_DECODE_PIXELS must be positive") long maxDecodePixels,
    @Min(value = 1, message = "MEDIA_MAX_SOURCE_EDGE must be positive") int maxSourceEdge
) {
    public MediaProperties {
        if (root == null || root.isBlank()) root = "/data/media";
        if (maxUploadBytes <= 0) maxUploadBytes = 10485760;
        if (maxEdgePixels <= 0) maxEdgePixels = 1600;
        if (maxDecodePixels <= 0) maxDecodePixels = 40_000_000;
        if (maxSourceEdge <= 0) maxSourceEdge = 10_000;
    }

    public Path rootPath() {
        return Path.of(root).toAbsolutePath().normalize();
    }
}
