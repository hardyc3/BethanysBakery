package com.bethanysbakery.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.config.MediaProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaStorageTest {

    @TempDir
    Path root;

    @Test
    void resolvesGeneratedKeysUnderRoot() throws Exception {
        MediaStorage storage = new MediaStorage(new MediaProperties(root.toString(), 10_485_760, 1600, 40_000_000, 10_000));
        storage.write("2026/11/uuid/original.webp", new byte[] {1, 2, 3});

        assertThat(storage.read("2026/11/uuid/original.webp")).containsExactly(1, 2, 3);
        assertThat(root.resolve("2026/11/uuid/original.webp")).exists();
    }

    @Test
    void rejectsTraversalKeys() {
        MediaStorage storage = new MediaStorage(new MediaProperties(root.toString(), 10_485_760, 1600, 40_000_000, 10_000));

        assertThatThrownBy(() -> storage.resolve("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("etc/passwd")).doesNotExist();
    }
}
