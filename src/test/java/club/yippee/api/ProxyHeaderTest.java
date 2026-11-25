package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import club.yippee.api.account.Mailer;
import club.yippee.api.common.Digests;

/**
 * Behind a load balancer (FORWARD_HEADERS_STRATEGY=native) the server must count each client by
 * its own address, not the balancer's. This needs the real embedded server, so it uses a real port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The list settings are left blank here, as they are on a server where the variables are not set.
        properties = {"server.forward-headers-strategy=native", "app.required-agreements=", "app.admin-emails=",
                "app.cors-allowed-origins="})
@ActiveProfiles("test")
class ProxyHeaderTest {

    @Value("${local.server.port}")
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Digests digests;
    @MockitoBean
    Mailer mailer;

    private int check(String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/identifiers/check"))
                .header("Content-Type", "application/json").header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"identifier\":\"parent@example.com\"}")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void linksInResponsesAreHttpsWhenTheProxySaysTheClientUsedHttps() throws Exception {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.sql("insert into activities (title, description, age_min_months, age_max_months, difficulty, duration_min,"
                        + " duration_max, price, image_path, pdf_path, is_public, created_at, updated_at)"
                        + " values ('프록시 확인', '설명', 60, 65, 'easy', 5, 5, 0, 'activities/c.png', 'activities/p.pdf', true, ?, ?)")
                .params(now, now).update();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/activities?page_size=1"))
                .header("X-Forwarded-For", "203.0.113.7").header("X-Forwarded-Proto", "https").GET().build();
        String body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        assertThat(body).contains("\"image\":\"https://").contains("\"next\":").doesNotContain("\"image\":\"http://");
    }

    @Autowired
    club.yippee.api.common.AppProperties properties;

    @Test
    void unsetListSettingsMeanNoneAndSignupStillWorksWithoutRequiredConsents() throws Exception {
        assertThat(properties.requiredAgreements()).isEmpty();
        assertThat(properties.adminEmails()).isEmpty();
        assertThat(properties.corsAllowedOrigins()).isEmpty();

        String proof = Digests.randomToken();
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        String email = "blank-" + Digests.randomHex(4) + "@example.com";
        jdbc.sql("insert into email_verifications (id, email, purpose, code_digest, attempts, expires_at,"
                        + " verified_at, token_digest, token_expires_at, created_at) values (?, ?, 'signup', '', 0, ?, ?, ?, ?, ?)")
                .params("ver_" + Digests.randomHex(8), email, now.plusMinutes(3), now,
                        digests.of("verification-token", proof), now.plusMinutes(10), now).update();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"identifier\":\"" + email + "\",\"verification_token\":\""
                        + proof + "\",\"password\":\"SecureAccountPwd!83927\",\"agreements\":{}}")).build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
    }

    @Test
    void eachClientBehindTheProxyHasItsOwnLimit() throws Exception {
        jdbc.sql("delete from auth_rate_limits").update();
        jdbc.sql("insert into auth_rate_limits (key_digest, window_start, hits) values (?, ?, 999)")
                .params(digests.of("rate", "identifier-check:ip:203.0.113.7"), Instant.now().getEpochSecond()).update();
        assertThat(check("203.0.113.7")).isEqualTo(200);
        assertThat(check("203.0.113.7")).isEqualTo(429);
        assertThat(check("198.51.100.9")).isEqualTo(200);
    }
}
