package com.bethanysbakery.api.catalog;

import com.bethanysbakery.api.api.catalog.CatalogApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.config.MediaProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.catalog.Media;
import com.bethanysbakery.api.domain.catalog.MediaRepository;
import com.bethanysbakery.api.domain.catalog.MediaStatus;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final List<Integer> SIZES = List.of(400, 800, 1600);

    private final MediaRepository mediaRepository;
    private final MediaReferenceService mediaReferences;
    private final ImageProcessor imageProcessor;
    private final MediaStorage storage;
    private final MediaProperties properties;
    private final AuditService audit;

    public MediaService(MediaRepository mediaRepository, MediaReferenceService mediaReferences,
            ImageProcessor imageProcessor, MediaStorage storage, MediaProperties properties, AuditService audit) {
        this.mediaRepository = mediaRepository;
        this.mediaReferences = mediaReferences;
        this.imageProcessor = imageProcessor;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
    }

    @Transactional
    public CatalogApi.MediaView upload(MultipartFile file, UUID adminId) {
        if (file == null || file.isEmpty()) {
            throw new RequestValidationException("file", "A non-empty image file is required");
        }
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new AuthException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Upload exceeds the 10 MB limit");
        }

        byte[] uploaded;
        try {
            uploaded = file.getBytes();
        } catch (IOException ex) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unable to read uploaded file");
        }
        ImageProcessor.NormalizedImage image = imageProcessor.normalize(uploaded);
        UUID id = Uuids.v7();
        String prefix = storagePrefix(id);
        String originalKey = prefix + "/original.webp";
        String filename = sanitizeFilename(file.getOriginalFilename());

        Map<String, String> variantUrls = new LinkedHashMap<>();
        List<String> writtenKeys = new ArrayList<>();
        // Clean up files if the DB save or audit write rolls back after bytes hit disk.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) deleteFiles(writtenKeys);
                }
            });
        }
        try {
            storage.write(originalKey, image.bytes());
            writtenKeys.add(originalKey);
            for (Map.Entry<Integer, byte[]> entry : image.variants().entrySet()) {
                String key = prefix + "/" + entry.getKey() + ".webp";
                storage.write(key, entry.getValue());
                writtenKeys.add(key);
                variantUrls.put(String.valueOf(entry.getKey()), MediaViews.url(id, String.valueOf(entry.getKey())));
            }
        } catch (IOException ex) {
            deleteFiles(writtenKeys);
            throw new AuthException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Media storage failed");
        }

        Media media = new Media(id, originalKey, filename, "image/webp", image.bytes().length,
            image.width(), image.height(), variantUrls, image.sha256(), adminId, Instant.now());
        mediaRepository.save(media);
        audit.record(ActorType.ADMIN, adminId, "MEDIA_UPLOADED", "MEDIA", id, null,
            Map.of("storageKey", originalKey, "sha256", image.sha256(), "sizeBytes", image.bytes().length));
        return toView(media);
    }

    @Transactional(readOnly = true)
    public MediaAsset get(UUID mediaId, Integer size) {
        Media media = activeMedia(mediaId);
        if (size != null && !SIZES.contains(size)) {
            throw new RequestValidationException("size", "size must be 400, 800 or 1600");
        }
        String key = size == null ? media.getStorageKey() : variantKey(media, size);
        try {
            byte[] bytes = storage.read(key);
            String etag = "\"" + media.getSha256() + "-" + (size == null ? "original" : size) + "\"";
            return new MediaAsset(bytes, etag, "image/webp");
        } catch (IOException ex) {
            throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Media file not found");
        }
    }

    @Transactional
    public void delete(UUID mediaId, UUID adminId) {
        Media media = mediaReferences.assertDeletable(mediaId);
        media.markDeleted();
        mediaRepository.save(media);
        audit.record(ActorType.ADMIN, adminId, "MEDIA_DELETED", "MEDIA", mediaId,
            Map.of("storageKey", media.getStorageKey(), "status", "ACTIVE"),
            Map.of("storageKey", media.getStorageKey(), "status", "DELETED"));

        List<String> keys = new ArrayList<>();
        keys.add(media.getStorageKey());
        String parent = storageParent(media);
        SIZES.forEach(size -> keys.add(parent + size + ".webp"));
        // Remove files only after the DELETED status is committed so rollback cannot orphan a live row.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFiles(keys);
                }
            });
        } else {
            deleteFiles(keys);
        }
    }

    private void deleteFiles(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException ex) {
                log.warn("Failed to remove deleted media file {}", key, ex);
            }
        }
    }

    @Transactional(readOnly = true)
    public CatalogApi.MediaView view(UUID mediaId) {
        return toView(activeMedia(mediaId));
    }

    private Media activeMedia(UUID mediaId) {
        return mediaRepository.findByIdAndStatus(mediaId, MediaStatus.ACTIVE)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Media not found"));
    }

    private String variantKey(Media media, int size) {
        if (media.getVariants().containsKey(String.valueOf(size))) {
            return storageParent(media) + size + ".webp";
        }
        return media.getStorageKey();
    }

    private String storageParent(Media media) {
        String key = media.getStorageKey();
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    private CatalogApi.MediaView toView(Media media) {
        return MediaViews.of(media);
    }

    private String storagePrefix(UUID id) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return String.format("%04d/%02d/%s", date.getYear(), date.getMonthValue(), id);
    }

    /** Keeps user-controlled metadata only; never used to build a storage path. */
    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) return "upload";
        String cleaned = original.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        return cleaned.length() > 512 ? cleaned.substring(0, 512) : cleaned;
    }

    public record MediaAsset(byte[] bytes, String etag, String contentType) {}
}
