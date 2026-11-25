package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.RateLimiter;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.OtpChallenge;
import com.bethanysbakery.api.domain.auth.OtpChallengeRepository;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import com.bethanysbakery.api.domain.auth.OtpPurpose;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtpService {
    private static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    private static final Duration IP_WINDOW = Duration.ofHours(1);

    private final OtpChallengeRepository challenges;
    private final CustomerRepository customers;
    private final CryptoService crypto;
    private final OtpSender sender;
    private final OtpCodeGenerator codes;
    private final RateLimiter rateLimiter;
    private final BakeryProperties.Auth config;

    public OtpService(OtpChallengeRepository challenges, CustomerRepository customers, CryptoService crypto,
            OtpSender sender, OtpCodeGenerator codes, RateLimiter rateLimiter, BakeryProperties properties) {
        this.challenges = challenges;
        this.customers = customers;
        this.crypto = crypto;
        this.sender = sender;
        this.codes = codes;
        this.rateLimiter = rateLimiter;
        this.config = properties.auth();
    }

    @Transactional
    public OtpRequest requestLogin(OtpChannel channel, String destination, String ip) {
        DestinationNormalizer.Normalized normalized = DestinationNormalizer.normalize(channel, destination);
        return create(normalized, OtpPurpose.LOGIN, null, ip);
    }

    @Transactional
    public OtpRequest requestContactVerification(OtpChannel channel, String destination, UUID customerId, String ip) {
        DestinationNormalizer.Normalized normalized = DestinationNormalizer.normalize(channel, destination);
        return create(normalized, OtpPurpose.CONTACT_VERIFY, customerId, ip);
    }

    private OtpRequest create(DestinationNormalizer.Normalized destination, OtpPurpose purpose, UUID customerId,
            String ip) {
        Instant now = Instant.now();
        String destinationHash = crypto.sha256Hex(destination.value());

        Optional<OtpChallenge> last = challenges.findFirstByDestinationHashOrderByCreatedAtDesc(destinationHash);
        if (last.isPresent()) {
            Instant resendAt = last.get().getCreatedAt().plusSeconds(config.otpResendSeconds());
            if (resendAt.isAfter(now)) {
                throw AuthException.rateLimited(Math.max(1, Duration.between(now, resendAt).getSeconds()));
            }
        }
        long recentSends = challenges.countByDestinationHashAndCreatedAtAfter(destinationHash, now.minus(SEND_WINDOW));
        if (recentSends >= config.otpMaxSendsPer15m()) {
            Instant oldest = challenges
                .findFirstByDestinationHashAndCreatedAtAfterOrderByCreatedAtAsc(destinationHash, now.minus(SEND_WINDOW))
                .map(OtpChallenge::getCreatedAt)
                .orElse(now);
            throw AuthException.rateLimited(Math.max(1, Duration.between(now, oldest.plus(SEND_WINDOW)).getSeconds()));
        }
        RateLimiter.Result ipLimit = rateLimiter.tryAcquire("otp:ip:" + ip, 10, IP_WINDOW);
        if (!ipLimit.allowed()) {
            throw AuthException.rateLimited(ipLimit.retryAfterSeconds());
        }

        String code = codes.generate();
        String payload = customerId == null ? destination.value() : customerId + "|" + destination.value();
        OtpChallenge challenge = new OtpChallenge(
            Uuids.v7(), destination.channel(), purpose, destinationHash, crypto.encrypt(payload),
            crypto.hmacSha256Hex(code), config.otpMaxAttempts(), now.plus(config.otpTtl()), now
        );
        challenges.save(challenge);

        boolean deliver = true;
        if (purpose == OtpPurpose.LOGIN) {
            Optional<Customer> existing = findCustomer(destination.channel(), destination.value());
            deliver = existing.isEmpty() || existing.get().getStatus() != CustomerStatus.DISABLED;
        }
        if (deliver) {
            sender.send(destination.channel(), destination.value(), code, challenge.getExpiresAt());
        }
        return new OtpRequest(challenge, destination.channel(), destination.masked());
    }

    @Transactional(noRollbackFor = AuthException.class)
    public VerifiedOtp verify(UUID challengeId, String code, OtpPurpose expectedPurpose, String ip) {
        RateLimiter.Result ipLimit = rateLimiter.tryAcquire("otpverify:ip:" + ip, 20, IP_WINDOW);
        if (!ipLimit.allowed()) {
            throw AuthException.rateLimited(ipLimit.retryAfterSeconds());
        }
        OtpChallenge challenge = challenges.findByIdForUpdate(challengeId)
            .orElseThrow(() -> AuthException.badRequest("OTP_INVALID", "Invalid or expired code"));
        if (challenge.getPurpose() != expectedPurpose) {
            throw AuthException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
        if (challenge.isConsumed()) {
            throw AuthException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
        Instant now = Instant.now();
        if (challenge.isExpired(now)) {
            throw AuthException.badRequest("OTP_EXPIRED", "Code expired");
        }
        if (challenge.isLocked()) {
            throw AuthException.badRequest("OTP_LOCKED", "Too many attempts");
        }
        if (!crypto.constantTimeEquals(challenge.getCodeHash(), crypto.hmacSha256Hex(code))) {
            challenge.incrementAttempts();
            challenges.save(challenge);
            throw AuthException.badRequest("OTP_INVALID", "Invalid or expired code");
        }
        challenge.consume(now);
        challenges.save(challenge);

        String payload = crypto.decrypt(challenge.getDestinationEncrypted());
        UUID customerId = null;
        String destination = payload;
        if (expectedPurpose == OtpPurpose.CONTACT_VERIFY) {
            int separator = payload.indexOf('|');
            if (separator <= 0) {
                throw AuthException.badRequest("OTP_INVALID", "Invalid or expired code");
            }
            customerId = UUID.fromString(payload.substring(0, separator));
            destination = payload.substring(separator + 1);
        }
        return new VerifiedOtp(challenge, destination, customerId);
    }

    private Optional<Customer> findCustomer(OtpChannel channel, String destination) {
        return channel == OtpChannel.EMAIL
            ? customers.findByEmail(destination)
            : customers.findByPhoneE164(destination);
    }

    public record OtpRequest(OtpChallenge challenge, OtpChannel channel, String destinationMasked) {}
    public record VerifiedOtp(OtpChallenge challenge, String destination, UUID customerId) {}
}
