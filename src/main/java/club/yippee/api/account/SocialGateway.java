package club.yippee.api.account;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Fields;

/**
 * Kakao, Naver and Google sign-in: trades the authorization code the frontend received for the
 * provider's token, then asks the provider who that is. The only class that knows the providers'
 * addresses and field names. Codes, tokens, secrets and emails are never logged.
 */
@Component
public class SocialGateway {

    /**
     * Who the provider says this is. The id is the provider's own, stable number for the user.
     * The email is null when none usable came; verified is true only when the provider itself
     * vouches that the address belongs to this user. The name is what the provider calls the user,
     * empty when it gave none that fits our column.
     */
    public record Identity(String id, String email, boolean emailVerified, String name) {
    }

    private static final Logger log = LoggerFactory.getLogger(SocialGateway.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, AppProperties.Social> providers;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    SocialGateway(AppProperties properties) {
        this.providers = properties.social();
    }

    /** Whether this provider is one of ours and has its keys. Kakao alone can run without a secret. */
    public boolean offers(String provider) {
        AppProperties.Social setup = providers.get(provider);
        return setup != null && !setup.clientId().isEmpty()
                && ("kakao".equals(provider) || !setup.clientSecret().isEmpty());
    }

    /** 400 when the provider refuses the code, 503 when the provider or our own setup is at fault. */
    public Identity identify(String provider, String code, String redirectUri, String state) {
        AppProperties.Social setup = providers.get(provider);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", setup.clientId());
        if (!setup.clientSecret().isEmpty()) {
            form.put("client_secret", setup.clientSecret());
        }
        form.put("code", code);
        if (redirectUri != null) {
            form.put("redirect_uri", redirectUri);
        }
        if (state != null) {
            form.put("state", state);
        }
        String encoded = form.entrySet().stream()
                .map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpResponse<String> answer = send(provider, "token", HttpRequest.newBuilder(URI.create(setup.tokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(encoded)));
        Map<?, ?> token = body(provider, "token", answer);
        if (!(token.get("access_token") instanceof String access) || access.isEmpty()) {
            // The provider's error word is its own text: logged only when it is a plain code word.
            String error = token.get("error") instanceof String word && word.matches("[A-Za-z0-9_]{1,40}") ? word : "-";
            if (answer.statusCode() == 401 || "invalid_client".equals(error)) {
                // Our key or secret is wrong. That is not the user's fault and not to be retried by them.
                log.error("social_setup_refused provider={} status={} error={}", provider, answer.statusCode(), error);
                throw unavailable();
            }
            log.warn("social_code_refused provider={} status={} error={}", provider, answer.statusCode(), error);
            throw ApiException.invalid("소셜 로그인 인증을 확인해 주세요. 처음부터 다시 시도해 주세요.");
        }
        HttpResponse<String> me = send(provider, "profile", HttpRequest.newBuilder(URI.create(setup.profileUrl()))
                .header("Authorization", "Bearer " + access).GET());
        if (me.statusCode() != 200) {
            log.error("social_profile_refused provider={} status={}", provider, me.statusCode());
            throw unavailable();
        }
        Identity identity = switch (provider) {
            case "kakao" -> kakao(body(provider, "profile", me));
            case "naver" -> naver(body(provider, "profile", me));
            default -> google(body(provider, "profile", me));
        };
        if (identity == null || identity.id().isEmpty() || identity.id().length() > 255) {
            log.error("social_profile_unreadable provider={}", provider);
            throw unavailable();
        }
        return identity;
    }

    // The email counts only when Kakao says it is valid: an expired one comes partly masked.
    private static Identity kakao(Map<?, ?> profile) {
        Object id = profile.get("id");
        if (!(id instanceof Number) && !(id instanceof String)) {
            return null;
        }
        Map<?, ?> account = profile.get("kakao_account") instanceof Map<?, ?> map ? map : Map.of();
        boolean valid = Boolean.TRUE.equals(account.get("is_email_valid"));
        Map<?, ?> shown = account.get("profile") instanceof Map<?, ?> map ? map : Map.of();
        return new Identity(String.valueOf(id), valid ? email(account.get("email")) : null,
                Boolean.TRUE.equals(account.get("is_email_verified")), name(shown.get("nickname")));
    }

    // Naver's profile says nothing about whether the contact email was verified, and the user may
    // have changed it to any address, so it is never taken as proven.
    private static Identity naver(Map<?, ?> profile) {
        if (!"00".equals(profile.get("resultcode")) || !(profile.get("response") instanceof Map<?, ?> response)
                || !(response.get("id") instanceof String id)) {
            return null;
        }
        return new Identity(id, email(response.get("email")), false, name(response.get("name")));
    }

    // Google vouches for an address only when it runs the mailbox: gmail.com, or a hosted domain ("hd").
    // Its documentation shows email_verified both as a boolean and as the string "true".
    private static Identity google(Map<?, ?> profile) {
        if (!(profile.get("sub") instanceof String id)) {
            return null;
        }
        String email = email(profile.get("email"));
        boolean verified = "true".equals(String.valueOf(profile.get("email_verified")));
        boolean googles = email != null && (email.endsWith("@gmail.com")
                || (profile.get("hd") instanceof String domain && !domain.isEmpty()));
        return new Identity(id, email, verified && googles, name(profile.get("name")));
    }

    // Only a starting value the user can change, so one that does not fit is dropped, not cut.
    private static String name(Object value) {
        String text = value instanceof String given ? given.strip() : "";
        return text.length() <= 100 ? text : "";
    }

    private static String email(Object value) {
        return value instanceof String text ? Fields.normalEmail(text) : null;
    }

    private HttpResponse<String> send(String provider, String step, HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500) {
                log.warn("social_provider_error provider={} step={} status={}", provider, step, response.statusCode());
                throw unavailable();
            }
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException noAnswer) {
            log.warn("social_no_answer provider={} step={} {}", provider, step, noAnswer.getClass().getName());
            throw unavailable();
        }
    }

    private Map<?, ?> body(String provider, String step, HttpResponse<String> response) {
        Map<?, ?> answer = null;
        try {
            answer = json.readValue(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            // Falls through to the same answer as a JSON "null".
        }
        if (answer == null) {
            log.warn("social_answer_unreadable provider={} step={} status={}", provider, step, response.statusCode());
            throw unavailable();
        }
        return answer;
    }

    private static ApiException unavailable() {
        return new ApiException(503, "SERVICE_UNAVAILABLE", "지금은 소셜 로그인을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
