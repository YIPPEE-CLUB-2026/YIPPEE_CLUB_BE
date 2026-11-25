package club.yippee.api.common;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String secret, String fromEmail, String passwordResetFrontendUrl,
                            List<String> corsAllowedOrigins, String filesRoot, String tossSecretKey,
                            String tossBaseUrl, List<String> adminEmails, List<String> requiredAgreements,
                            Auth auth, java.util.Map<String, Social> social) {

    public AppProperties {
        social = social == null ? java.util.Map.of() : social;
        // An unset variable must mean "none", never a list holding one empty name.
        requiredAgreements = requiredAgreements == null ? List.of()
                : requiredAgreements.stream().map(String::strip).filter(key -> !key.isEmpty()).toList();
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of()
                : corsAllowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
        adminEmails = adminEmails == null ? List.of()
                : adminEmails.stream().map(email -> email.strip().toLowerCase(java.util.Locale.ROOT)).toList();
        // The key signs access tokens and every stored digest; HS256 needs 256 bits.
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("SECRET_KEY must be at least 32 bytes long.");
        }
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
        tossSecretKey = tossSecretKey == null ? "" : tossSecretKey;
        // The order lookup that settles webhooks works only with a secret ("sk") key.
        if (!tossSecretKey.isEmpty() && !tossSecretKey.startsWith("test_sk") && !tossSecretKey.startsWith("live_sk")) {
            throw new IllegalStateException("TOSS_SECRET_KEY must be a test_sk or live_sk key.");
        }
    }

    /** One sign-in provider. Without a client id the provider is off and its address answers 404. */
    public record Social(String clientId, String clientSecret, String tokenUrl, String profileUrl) {
        public Social {
            clientId = clientId == null ? "" : clientId.strip();
            clientSecret = clientSecret == null ? "" : clientSecret.strip();
        }
    }

    public record Auth(int codeTtlSeconds, int verificationTokenTtlSeconds, int codeMaxAttempts,
                       int sendCooldownSeconds, int rateWindowSeconds, int ipLimit, int emailLimit,
                       int accessTtlSeconds, int refreshTtlSeconds, int passwordResetTtlSeconds) {
    }
}
