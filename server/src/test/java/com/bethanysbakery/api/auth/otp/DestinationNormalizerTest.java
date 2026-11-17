package com.bethanysbakery.api.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DestinationNormalizerTest {

    @Test
    void normalizesEmailToLowercase() {
        DestinationNormalizer.Normalized normalized =
            DestinationNormalizer.normalize(OtpChannel.EMAIL, "  Bethany@Cherry-Home.com ");

        assertThat(normalized.channel()).isEqualTo(OtpChannel.EMAIL);
        assertThat(normalized.value()).isEqualTo("bethany@cherry-home.com");
        assertThat(normalized.masked()).isEqualTo("b***@cherry-home.com");
    }

    @Test
    void rejectsBlankAndMalformedEmails() {
        assertThatThrownBy(() -> DestinationNormalizer.normalize(OtpChannel.EMAIL, "  "))
            .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> DestinationNormalizer.normalize(OtpChannel.EMAIL, "not-an-email"))
            .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> DestinationNormalizer.normalize(OtpChannel.EMAIL, "a@b"))
            .isInstanceOf(AuthException.class);
    }

    @Test
    void normalizesPhoneVariantsToE164() {
        assertThat(DestinationNormalizer.normalize(OtpChannel.SMS, "(555) 555-0103").value())
            .isEqualTo("+15555550103");
        assertThat(DestinationNormalizer.normalize(OtpChannel.SMS, "+44 20 7946 0958").value())
            .isEqualTo("+442079460958");
        assertThat(DestinationNormalizer.normalize(OtpChannel.SMS, "15555550104").value())
            .isEqualTo("+15555550104");
    }

    @Test
    void masksPhoneAndRejectsTooShortNumbers() {
        assertThat(DestinationNormalizer.normalize(OtpChannel.SMS, "+15555550103").masked())
            .isEqualTo("+155***0103");

        assertThatThrownBy(() -> DestinationNormalizer.normalize(OtpChannel.SMS, "12345"))
            .isInstanceOf(AuthException.class);
    }

    @Test
    void normalizerErrorsAreValidationProblems() {
        AuthException ex = catchValidation(() -> DestinationNormalizer.normalize(OtpChannel.SMS, "nope"));

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("VALIDATION_ERROR");
    }

    private AuthException catchValidation(Runnable action) {
        try {
            action.run();
        } catch (AuthException ex) {
            return ex;
        }
        throw new AssertionError("Expected AuthException");
    }
}
