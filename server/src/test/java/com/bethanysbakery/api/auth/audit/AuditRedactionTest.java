package com.bethanysbakery.api.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditRedactionTest {

    @Test
    void freeTextAuditFieldsDoNotRetainEmailsPhonesOrSecrets() {
        String scrubbed = AuditService.scrubFreeText(
            "Customer buyer@example.com called +15551234567; api_key=abcdef1234567890 otp 123456");
        assertThat(scrubbed).doesNotContain("buyer@example.com", "+15551234567", "abcdef1234567890", "123456");
        assertThat(scrubbed).contains("[REDACTED]");
    }
}
