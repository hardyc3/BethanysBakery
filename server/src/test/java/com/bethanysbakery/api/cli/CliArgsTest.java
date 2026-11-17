package com.bethanysbakery.api.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CliArgsTest {

    @Test
    void parsesEqualsAndSeparateValueForms() {
        assertThat(CliArgs.parse(new String[] {
            "--bootstrap-admin", "--email=admin@example.com", "--name", "Bethany"
        }))
            .containsEntry("bootstrap-admin", "true")
            .containsEntry("email", "admin@example.com")
            .containsEntry("name", "Bethany");
    }

    @Test
    void parsesFlagValuesOnlyForKnownOptions() {
        assertThat(CliArgs.parse(new String[] { "junk", "--pair-device", "--unknown" }))
            .containsEntry("pair-device", "true")
            .doesNotContainKey("junk");
    }
}
