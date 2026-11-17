package com.bethanysbakery.api.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QrCodeRendererTest {

    private final QrCodeRenderer renderer = new QrCodeRenderer();

    @Test
    void rendersAsciiQrForUri() {
        String output = renderer.render("otpauth://totp/test?secret=ABC");

        assertThat(output).isNotBlank().contains("█");
        assertThat(output.lines()).hasSizeGreaterThan(20);
    }

    @Test
    void outputChangesWithContent() {
        assertThat(renderer.render("otpauth://totp/a?secret=AAA"))
            .isNotEqualTo(renderer.render("otpauth://totp/b?secret=BBB"));
    }
}
