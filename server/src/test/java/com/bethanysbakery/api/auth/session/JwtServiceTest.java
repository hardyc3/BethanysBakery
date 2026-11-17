package com.bethanysbakery.api.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwt = new JwtService(TestProperties.defaults());

    @Test
    void issuesAndParsesAccessTokens() {
        UUID subject = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        UUID device = UUID.randomUUID();

        String token = jwt.issue(SubjectType.ADMIN, subject, family, device, Instant.now(), Duration.ofMinutes(15));
        JwtService.AccessClaims claims = jwt.parse(token, SubjectType.ADMIN);

        assertThat(claims.subjectId()).isEqualTo(subject);
        assertThat(claims.sessionId()).isEqualTo(family);
        assertThat(claims.deviceId()).isEqualTo(device);
        assertThat(claims.type()).isEqualTo(SubjectType.ADMIN);
        assertThat(claims.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void rejectsTokensForAnotherAudience() {
        String token = jwt.issue(SubjectType.CUSTOMER, UUID.randomUUID(), UUID.randomUUID(), null,
            Instant.now(), Duration.ofMinutes(15));

        assertThatThrownBy(() -> jwt.parse(token, SubjectType.ADMIN))
            .isInstanceOf(AuthException.class);
    }

    @Test
    void rejectsExpiredAndTamperedTokens() {
        String expired = jwt.issue(SubjectType.CUSTOMER, UUID.randomUUID(), UUID.randomUUID(), null,
            Instant.now().minus(Duration.ofHours(1)), Duration.ofMinutes(15));
        assertThatThrownBy(() -> jwt.parse(expired, SubjectType.CUSTOMER)).isInstanceOf(AuthException.class);

        String valid = jwt.issue(SubjectType.CUSTOMER, UUID.randomUUID(), UUID.randomUUID(), null,
            Instant.now(), Duration.ofMinutes(15));
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> jwt.parse(tampered, SubjectType.CUSTOMER)).isInstanceOf(AuthException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwt.parse("not-a-jwt", SubjectType.CUSTOMER)).isInstanceOf(AuthException.class);
    }
}
