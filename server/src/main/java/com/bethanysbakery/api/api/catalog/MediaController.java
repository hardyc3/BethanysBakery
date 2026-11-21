package com.bethanysbakery.api.api.catalog;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.RateLimiter;
import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.common.web.ClientIps;
import com.bethanysbakery.api.catalog.MediaService;
import com.bethanysbakery.api.catalog.MediaService.MediaAsset;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MediaController {

    private static final String CACHE_CONTROL = CacheControl.maxAge(365, TimeUnit.DAYS)
        .cachePublic().immutable().getHeaderValue();

    private static final int UPLOAD_LIMIT = 20;
    private static final Duration UPLOAD_WINDOW = Duration.ofHours(1);
    private static final int READ_LIMIT = 120;
    private static final Duration READ_WINDOW = Duration.ofMinutes(1);

    private final MediaService service;
    private final RateLimiter rateLimiter;

    public MediaController(MediaService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(value = "/admin/v1/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogApi.MediaView upload(@RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AdminPrincipal principal) {
        requireRate("media-upload:" + principal.adminId(), UPLOAD_LIMIT, UPLOAD_WINDOW);
        return service.upload(file, principal.adminId());
    }

    @DeleteMapping("/admin/v1/media/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID mediaId, @AuthenticationPrincipal AdminPrincipal principal) {
        service.delete(mediaId, principal.adminId());
    }

    @GetMapping(value = "/api/v1/media/{mediaId}", produces = "image/webp")
    public ResponseEntity<byte[]> get(@PathVariable UUID mediaId,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest httpRequest) {
        requireRate("media-read-ip:" + ClientIps.from(httpRequest), READ_LIMIT, READ_WINDOW);
        MediaAsset asset = service.get(mediaId, size);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(asset.contentType()));
        headers.setCacheControl(CACHE_CONTROL);
        headers.setETag(asset.etag());
        if (asset.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        return ResponseEntity.ok().headers(headers).body(asset.bytes());
    }

    private void requireRate(String key, int limit, Duration window) {
        RateLimiter.Result result = rateLimiter.tryAcquire(key, limit, window);
        if (!result.allowed()) {
            throw AuthException.rateLimited(result.retryAfterSeconds());
        }
    }
}
