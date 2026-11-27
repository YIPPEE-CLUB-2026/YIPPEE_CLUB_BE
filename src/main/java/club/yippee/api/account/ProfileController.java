package club.yippee.api.account;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.common.Fields;
import club.yippee.api.common.RateLimiter;

/** The signed-in user's own profile and password. The user always comes from the token. */
@RestController
@RequestMapping("/api/v1/users/me")
public class ProfileController {

    private final AccountService accounts;
    private final RateLimiter limiter;
    private final RefreshSessionRepository sessions;
    private final EmailVerificationRepository verifications;
    private final JdbcClient jdbc;

    ProfileController(AccountService accounts, RateLimiter limiter, RefreshSessionRepository sessions,
                      EmailVerificationRepository verifications, JdbcClient jdbc) {
        this.accounts = accounts;
        this.limiter = limiter;
        this.sessions = sessions;
        this.verifications = verifications;
        this.jdbc = jdbc;
    }

    @GetMapping
    Map<String, Object> profile(@AuthenticationPrincipal Long userId) {
        return accounts.profile(userId);
    }

    @PatchMapping
    Map<String, Object> update(@AuthenticationPrincipal Long userId, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "name", "nickname", "relation", "age_group");
        String name = optionalText(fields, "name", 100);
        String nickname = optionalText(fields, "nickname", 10);
        if (nickname != null && !nickname.isEmpty() && !NICKNAME.matcher(nickname).matches()) {
            fields.reject("nickname");
        }
        String relation = optionalChoice(fields, "relation", RELATIONS);
        String ageGroup = optionalChoice(fields, "age_group", AGE_GROUPS);
        fields.check();
        return accounts.updateProfile(userId, name, nickname, relation, ageGroup);
    }

    /** 2 to 10 characters of Korean syllables, English letters and digits; single spaces may stand inside ("도영 엄마"). */
    private static final java.util.regex.Pattern NICKNAME =
            java.util.regex.Pattern.compile("(?=.{2,10}$)[가-힣A-Za-z0-9]+( [가-힣A-Za-z0-9]+)*");
    private static final java.util.Set<String> RELATIONS = java.util.Set.of("mother", "father", "grandparent", "other");
    private static final java.util.Set<String> AGE_GROUPS = java.util.Set.of("20s", "30s", "40s", "50s_plus");

    // Left out: null, meaning "keep". Sent as JSON null: "", meaning "clear". Otherwise one of the known values.
    private static String optionalChoice(Fields fields, String name, java.util.Set<String> known) {
        if (!fields.has(name)) {
            return null;
        }
        if (fields.isNull(name)) {
            return "";
        }
        String value = fields.string(name);
        if (value == null || !known.contains(value)) {
            fields.reject(name);
        }
        return value;
    }

    // Left out: null, meaning "keep". Sent as JSON null: "", meaning "clear".
    private static String optionalText(Fields fields, String name, int max) {
        if (!fields.has(name)) {
            return null;
        }
        return fields.isNull(name) ? "" : fields.text(name, max, true);
    }

    @PatchMapping("/password")
    Map<String, Object> changePassword(@AuthenticationPrincipal Long userId,
                                       @RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "password-change");
        limiter.identity("password-change", String.valueOf(userId));
        Fields fields = Fields.strict(body, "current_password", "new_password");
        String current = fields.string("current_password");
        String next = fields.string("new_password");
        fields.check();
        accounts.changePassword(userId, current, next);
        return AuthController.DONE;
    }

    /** The member leaves. See AccountService.withdraw for what is destroyed and what is kept. */
    @org.springframework.web.bind.annotation.DeleteMapping
    Map<String, Object> withdraw(@AuthenticationPrincipal Long userId) {
        accounts.withdraw(userId);
        return AuthController.DONE;
    }

    /** Nightly: rows that can no longer be used are removed, so the three tables stop growing. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpired() {
        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        sessions.deleteExpiredBefore(dayAgo);
        verifications.deleteCreatedBefore(dayAgo);
        jdbc.sql("delete from auth_rate_limits where window_start < ?").param(dayAgo.getEpochSecond()).update();
        accounts.purgeWithdrawn();
    }
}
