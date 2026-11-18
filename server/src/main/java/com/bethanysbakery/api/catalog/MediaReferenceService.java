package com.bethanysbakery.api.catalog;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.common.error.RequestValidationException;
import com.bethanysbakery.api.domain.catalog.ItemTemplateRepository;
import com.bethanysbakery.api.domain.catalog.Media;
import com.bethanysbakery.api.domain.catalog.MediaRepository;
import com.bethanysbakery.api.domain.catalog.MediaStatus;
import com.bethanysbakery.api.domain.menu.OfferingRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrency-safe media reference enforcement shared by catalog templates and S4 offerings.
 *
 * Every operation that creates a media reference and every media delete acquires a pessimistic
 * write lock on the same {@code media} row. Under READ_COMMITTED this serialises the two flows:
 * a delete cannot commit after a reference was created (and vice versa), so a DELETED media row
 * can never be left referenced. See S7 "Media hardening ownership".
 */
@Service
public class MediaReferenceService {

    private final MediaRepository mediaRepository;
    private final ItemTemplateRepository templateRepository;
    private final OfferingRepository offeringRepository;

    public MediaReferenceService(MediaRepository mediaRepository, ItemTemplateRepository templateRepository,
            OfferingRepository offeringRepository) {
        this.mediaRepository = mediaRepository;
        this.templateRepository = templateRepository;
        this.offeringRepository = offeringRepository;
    }

    /**
     * Locks the media row and returns whether it exists and is ACTIVE. Callers must invoke this
     * inside the same transaction that persists the reference so the lock is held until commit.
     */
    @Transactional
    public boolean lockAndCheckActive(UUID mediaId) {
        return mediaRepository.findByIdForUpdate(mediaId)
            .filter(media -> media.getStatus() == MediaStatus.ACTIVE)
            .isPresent();
    }

    /** Locks the media row and rejects a missing/inactive media with a field validation error. */
    @Transactional
    public void requireActive(UUID mediaId) {
        if (!lockAndCheckActive(mediaId)) {
            throw new RequestValidationException("photoMediaId", "must reference active media");
        }
    }

    /**
     * Locks the media row and rejects deletion while any template or offering references it.
     * Returns the ACTIVE media so callers can proceed with the delete in the same transaction.
     */
    @Transactional
    public Media assertDeletable(UUID mediaId) {
        Media media = mediaRepository.findByIdForUpdate(mediaId)
            .filter(candidate -> candidate.getStatus() == MediaStatus.ACTIVE)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Media not found"));
        if (templateRepository.existsByPhotoMediaId(mediaId)
                || offeringRepository.existsByPhotoMediaId(mediaId)) {
            throw new AuthException(HttpStatus.CONFLICT, "MEDIA_IN_USE",
                "Media cannot be deleted while a template or offering references it");
        }
        return media;
    }
}
