package com.bethanysbakery.api.catalog;

import com.bethanysbakery.api.api.catalog.CatalogApi;
import com.bethanysbakery.api.domain.catalog.Media;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Single source of truth for public media URLs and views built from persisted media. */
final class MediaViews {

    private MediaViews() {}

    static CatalogApi.MediaView of(Media media) {
        Map<String, String> variants = new LinkedHashMap<>();
        media.getVariants().keySet().forEach(key -> variants.put(key, url(media.getId(), key)));
        return new CatalogApi.MediaView(media.getId(), url(media.getId(), null),
            media.getWidth(), media.getHeight(), media.getSizeBytes(), variants);
    }

    static String url(UUID mediaId, String size) {
        String base = "/api/v1/media/" + mediaId;
        return size == null ? base : base + "?size=" + size;
    }
}
