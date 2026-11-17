package com.bethanysbakery.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {

    @Test
    void generatesVersion7WithVariant1() {
        UUID uuid = Uuids.v7();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void generatesUniqueValues() {
        Set<UUID> values = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            values.add(Uuids.v7());
        }
        assertThat(values).hasSize(1000);
    }
}
