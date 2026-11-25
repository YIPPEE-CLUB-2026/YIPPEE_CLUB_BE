package club.yippee.api.account;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Digests;
import club.yippee.api.common.SecurityConfig;

/**
 * The access token is a short-lived signed JWT. The refresh token is a random string whose
 * digest names a row in refresh_sessions, so it can be revoked.
 */
@Component
public class Tokens {

    static final String REFRESH = "refresh";

    private final JwtEncoder encoder;
    private final RefreshSessionRepository sessions;
    private final Digests digests;
    private final AppProperties.Auth auth;

    Tokens(JwtEncoder encoder, RefreshSessionRepository sessions, Digests digests, AppProperties properties) {
        this.encoder = encoder;
        this.sessions = sessions;
        this.digests = digests;
        this.auth = properties.auth();
    }

    /** Starts a session: a new access token and a new refresh token. */
    Map<String, Object> issue(User user) {
        String refresh = Digests.randomToken();
        sessions.save(new RefreshSession(user.id, digests.of(REFRESH, refresh),
                Instant.now().plusSeconds(auth.refreshTtlSeconds())));
        return pair(access(user), refresh);
    }

    String access(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.id))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(auth.accessTtlSeconds()))
                .claim(SecurityConfig.VERSION_CLAIM, user.tokenVersion)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    static Map<String, Object> pair(String access, String refresh) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", access);
        body.put("refresh_token", refresh);
        return body;
    }
}
