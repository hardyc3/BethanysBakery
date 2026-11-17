package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DestinationNormalizer {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private DestinationNormalizer() {}

    public static Normalized normalize(OtpChannel channel, String raw) {
        if (raw == null || raw.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Destination is required");
        }
        String value = raw.trim();
        if (channel == OtpChannel.EMAIL) {
            String email = value.toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(email).matches() || email.length() > 254) {
                throw AuthException.badRequest("VALIDATION_ERROR", "Invalid email address");
            }
            return new Normalized(OtpChannel.EMAIL, email, maskEmail(email));
        }
        String phone = normalizePhone(value);
        return new Normalized(OtpChannel.SMS, phone, maskPhone(phone));
    }

    private static String normalizePhone(String raw) {
        String cleaned = raw.replaceAll("[^0-9+]", "");
        if (cleaned.startsWith("+")) {
            String digits = cleaned.substring(1);
            if (digits.length() < 8 || digits.length() > 15) {
                throw AuthException.badRequest("VALIDATION_ERROR", "Invalid phone number");
            }
            return "+" + digits;
        }
        String digits = cleaned.replace("+", "");
        if (digits.length() == 10) return "+1" + digits;
        if (digits.length() == 11 && digits.startsWith("1")) return "+" + digits;
        if (digits.length() >= 8 && digits.length() <= 15) return "+" + digits;
        throw AuthException.badRequest("VALIDATION_ERROR", "Invalid phone number");
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        String digits = phone.startsWith("+") ? phone.substring(1) : phone;
        String prefix = digits.length() > 4 ? digits.substring(0, Math.min(3, digits.length() - 4)) : "";
        return "+" + prefix + "***" + digits.substring(Math.max(0, digits.length() - 4));
    }

    public record Normalized(OtpChannel channel, String value, String masked) {}
}
