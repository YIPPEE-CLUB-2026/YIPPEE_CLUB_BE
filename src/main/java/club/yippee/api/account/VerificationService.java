package club.yippee.api.account;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Digests;

/** The emailed six-digit code that must be confirmed before signup. */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    static final String CODE = "verification-code";
    static final String PROOF = "verification-token";

    private final EmailVerificationRepository verifications;
    private final Digests digests;
    private final Mailer mailer;
    private final AppProperties.Auth auth;

    VerificationService(EmailVerificationRepository verifications, Digests digests, Mailer mailer,
                        AppProperties properties) {
        this.verifications = verifications;
        this.digests = digests;
        this.mailer = mailer;
        this.auth = properties.auth();
    }

    /** Mails a new code. If the mail cannot be sent, the request row is not kept. */
    // Not one transaction: a slow mail server must not sit on a database connection while it answers.
    // The row is committed first and taken back if the mail fails.
    public EmailVerification send(String email) {
        String code = Digests.randomCode();
        String id = "ver_" + Digests.randomHex(24);
        EmailVerification challenge = verifications.save(new EmailVerification(id, email,
                digests.of(CODE, id + ":" + code), Instant.now().plusSeconds(auth.codeTtlSeconds())));
        try {
            mailer.send(email, "YIPPEE CLUB 이메일 인증",
                    "인증번호: " + code + "\n유효 시간: " + auth.codeTtlSeconds() + "초", null);
        } catch (Exception error) {
            // The exception text can carry the mail body or credentials: log its kind only.
            log.error("verification_email_send_failed {}", error.getClass().getName());
            verifications.deleteById(id);
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "인증 메일 발송에 실패했습니다.");
        }
        return challenge;
    }

    /** What a confirm attempt came to: the proof token, or the error to raise. */
    public record Outcome(String proofToken, ApiException error) {
    }

    /**
     * Checks a code. A failure is returned, not thrown, so the attempt counter commits;
     * the caller raises it afterwards.
     */
    @Transactional
    public Outcome confirm(String id, String code) {
        EmailVerification challenge = verifications.lockById(id).orElse(null);
        Instant now = Instant.now();
        if (challenge == null || !"signup".equals(challenge.purpose)) {
            return failed(ApiException.invalid("인증 요청을 확인해 주세요."));
        }
        if (challenge.verifiedAt != null || challenge.consumedAt != null) {
            return failed(new ApiException(409, "INVALID_STATE", "이미 사용한 인증 요청입니다."));
        }
        if (!challenge.expiresAt.isAfter(now)) {
            return failed(ApiException.invalid("인증번호가 만료되었습니다."));
        }
        if (challenge.attempts >= auth.codeMaxAttempts()) {
            return failed(new ApiException(429, "RATE_LIMITED", "인증 시도 횟수를 초과했습니다."));
        }
        challenge.attempts++;
        if (!Digests.same(challenge.codeDigest, digests.of(CODE, challenge.id + ":" + code))) {
            return failed(ApiException.invalid("인증번호가 올바르지 않습니다."));
        }
        String proof = Digests.randomToken();
        challenge.verifiedAt = now;
        challenge.tokenDigest = digests.of(PROOF, proof);
        challenge.tokenExpiresAt = now.plusSeconds(auth.verificationTokenTtlSeconds());
        challenge.codeDigest = "";
        return new Outcome(proof, null);
    }

    private static Outcome failed(ApiException error) {
        return new Outcome(null, error);
    }
}
