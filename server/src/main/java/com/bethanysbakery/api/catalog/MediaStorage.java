package com.bethanysbakery.api.catalog;

import com.bethanysbakery.api.config.MediaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Filesystem-backed media store. Storage keys are generated server-side and never come from uploads. */
@Component
public class MediaStorage {

    private final Path root;

    public MediaStorage(MediaProperties properties) {
        this.root = properties.rootPath();
    }

    public void write(String storageKey, byte[] bytes) throws IOException {
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    public byte[] read(String storageKey) throws IOException {
        return Files.readAllBytes(resolve(storageKey));
    }

    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    /** Resolves a key under the configured root and rejects any attempt to escape it. */
    public Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Empty media storage key");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Media storage key escapes media root");
        }
        return target;
    }
}
