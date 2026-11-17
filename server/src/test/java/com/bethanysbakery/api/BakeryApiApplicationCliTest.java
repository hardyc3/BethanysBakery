package com.bethanysbakery.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BakeryApiApplicationCliTest {

    @Test
    void detectsCliCommands() {
        assertThat(BakeryApiApplication.isCli(new String[] {"--bootstrap-admin"})).isTrue();
        assertThat(BakeryApiApplication.isCli(new String[] {"--bootstrap-admin=true"})).isTrue();
        assertThat(BakeryApiApplication.isCli(new String[] {"--pair-device"})).isTrue();
        assertThat(BakeryApiApplication.isCli(new String[] {"--pair-device=true"})).isTrue();
    }

    @Test
    void normalWebArgumentsAreNotCli() {
        assertThat(BakeryApiApplication.isCli(new String[] {"--server.port=0"})).isFalse();
        assertThat(BakeryApiApplication.isCli(new String[] {})).isFalse();
    }
}
