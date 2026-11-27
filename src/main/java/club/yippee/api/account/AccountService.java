package club.yippee.api.account;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Digests;

/** Signup, login, sessions, profile and passwords. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final String BAD_LOGIN = "이메일 또는 비밀번호를 확인해 주세요.";
    private static final String BAD_TOKEN = "토큰을 확인해 주세요.";
    private static final String BAD_RESET = "재설정 토큰을 확인해 주세요.";
    private static final String RESET = "password-reset";

    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final EmailVerificationRepository verifications;
    private final Tokens tokens;
    private final Digests digests;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final Mailer mailer;
    private final AppProperties properties;
    /** Checked when the email is unknown, so that case costs as much time as a wrong password. */
    private final String nobodysHash;
    private final JdbcClient jdbc;
    /** How long a withdrawn buyer's identity is kept beside the trade records (TRADE_RECORD_YEARS). */
    private final int tradeRecordYears;

    AccountService(UserRepository users, RefreshSessionRepository sessions, EmailVerificationRepository verifications,
                   Tokens tokens, Digests digests, PasswordPolicy policy, PasswordEncoder encoder, Mailer mailer,
                   AppProperties properties, JdbcClient jdbc,
                   @org.springframework.beans.factory.annotation.Value("${app.trade-record-years:5}") int tradeRecordYears) {
        this.jdbc = jdbc;
        if (tradeRecordYears < 1) {
            throw new IllegalStateException("TRADE_RECORD_YEARS must be at least 1.");
        }
        this.tradeRecordYears = tradeRecordYears;
        this.users = users;
        this.sessions = sessions;
        this.verifications = verifications;
        this.tokens = tokens;
        this.digests = digests;
        this.policy = policy;
        this.encoder = encoder;
        this.mailer = mailer;
        this.properties = properties;
        this.nobodysHash = encoder.encode(Digests.randomToken());
    }

    public boolean registered(String email) {
        return users.existsByEmail(email);
    }

    /** Creates the account from a verified email proof, which is used up in the same transaction. */
    @Transactional
    public Map<String, Object> signup(String email, String proofToken, String password, String agreements) {
        EmailVerification proof = verifications
                .lockByTokenDigest(digests.of(VerificationService.PROOF, proofToken)).orElse(null);
        Instant now = Instant.now();
        if (proof == null || !proof.email.equals(email) || !"signup".equals(proof.purpose)
                || proof.verifiedAt == null || proof.tokenExpiresAt == null || !proof.tokenExpiresAt.isAfter(now)) {
            throw ApiException.invalid("이메일 인증을 완료해 주세요.");
        }
        if (proof.consumedAt != null) {
            throw new ApiException(409, "INVALID_STATE", "이미 사용한 인증입니다.");
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(409, "ALREADY_EXISTS", "이미 가입한 이메일입니다.");
        }
        User user = users.saveAndFlush(new User(email, encoder.encode(password), agreements));
        proof.consumedAt = now;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", user.id);
        body.putAll(tokens.issue(user));
        return body;
    }

    /** One answer for a wrong password, an unknown email and a deactivated account. */
    @Transactional
    public Map<String, Object> login(String email, String password) {
        User user = users.lockByEmail(email).orElse(null);
        boolean fits = password.getBytes(StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BYTES;
        boolean matches = encoder.matches(fits ? password : "", user == null ? nobodysHash : user.passwordHash);
        if (user == null || !fits || !matches || !user.active) {
            throw ApiException.invalid(BAD_LOGIN);
        }
        user.lastLoginAt = Instant.now();
        Map<String, Object> body = tokens.issue(user);
        body.put("user", view(user));
        return body;
    }

    /**
     * Signs in whoever the provider named, making the account on the first visit. The provider's
     * own user number names the account from then on; the email is used only that first time.
     * A null agreements text means the consent a new account needs was not given.
     */
    @Transactional
    public Map<String, Object> socialLogin(String provider, SocialGateway.Identity who, String agreements) {
        Long linked = linkedUser(provider, who.id(), false);
        User user = null;
        boolean created = false;
        if (linked != null) {
            user = users.lockById(linked).orElseThrow();
            // While we waited for the user's row, a password reset or the owner's vouched sign-in may
            // have taken this door away. Looked at again under the lock, past our own snapshot.
            if (linkedUser(provider, who.id(), true) == null) {
                user = null;
            }
        }
        if (user == null) {
            if (who.email() == null) {
                throw new ApiException(409, "EMAIL_REQUIRED", "이메일 제공에 동의해야 가입할 수 있습니다.");
            }
            refuseAdmin(who.email());
            user = users.lockByEmail(who.email()).orElse(null);
            if (user != null && !who.emailVerified()) {
                // The provider does not vouch for this address, so it cannot open an account that has it.
                throw new ApiException(409, "ALREADY_EXISTS", "이미 가입한 이메일입니다. 이메일과 비밀번호로 로그인해 주세요.");
            }
            if (user != null) {
                // The address is proven to be this person's. Doors opened on it without proof may be
                // somebody else's: they go, and so does every session, before this one starts.
                removeUnprovenLinks(user);
            }
            if (user == null) {
                if (agreements == null) {
                    throw new ApiException(409, "AGREEMENTS_REQUIRED", "약관에 동의한 뒤 다시 시도해 주세요.");
                }
                // A password nobody knows: the account opens through the provider, or after a password reset.
                user = users.saveAndFlush(new User(who.email(), encoder.encode(Digests.randomToken()), agreements));
                created = true;
            }
            jdbc.sql("insert into social_accounts (user_id, provider, provider_user_id, email_verified, created_at)"
                            + " values (?, ?, ?, ?, ?)")
                    .params(user.id, provider, who.id(), who.emailVerified(),
                            java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).update();
        }
        // Also for an account linked before its address was put on the admin list.
        refuseAdmin(user.email);
        if (!user.active) {
            throw new ApiException(403, "PERMISSION_DENIED", "이용할 수 없는 계정입니다.");
        }
        if (user.name.isEmpty()) {
            // A starting value only: a name the user has set is never replaced.
            user.name = who.name();
        }
        user.lastLoginAt = Instant.now();
        Map<String, Object> body = tokens.issue(user);
        body.put("is_new_user", created);
        body.put("user", view(user));
        return body;
    }

    // Whose account this provider number opens, or null. The number is compared to the letter here:
    // the database's own comparison may ignore case, and Naver's numbers are mixed-case text.
    private Long linkedUser(String provider, String id, boolean underLock) {
        return jdbc.sql("select user_id, provider_user_id from social_accounts where provider = ? and provider_user_id = ?"
                        + (underLock ? " for update" : ""))
                .params(provider, id)
                .query((row, index) -> id.equals(row.getString("provider_user_id")) ? row.getLong("user_id") : null)
                .list().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    // Called once the mailbox is proven (a password reset, a vouched sign-in). If a door goes, the
    // sessions opened through it go too: every session ends and every access token stops working.
    private void removeUnprovenLinks(User user) {
        int removed = jdbc.sql("delete from social_accounts where user_id = ? and email_verified = false")
                .param(user.id).update();
        if (removed > 0) {
            user.tokenVersion++;
            sessions.revokeAll(user.id, Instant.now());
        }
    }

    // Admin is decided by email, so a provider's word about an email must never be a second way in.
    private void refuseAdmin(String email) {
        if (properties.adminEmails().contains(email)) {
            throw new ApiException(403, "PERMISSION_DENIED", "이 계정은 이메일과 비밀번호로만 로그인할 수 있습니다.");
        }
    }

    /** A new access token for a live session. The refresh token itself is not rotated. */
    @Transactional
    public Map<String, Object> refresh(String refreshToken) {
        RefreshSession session = usableSession(refreshToken);
        if (session.revokedAt != null) {
            throw ApiException.invalid(BAD_TOKEN);
        }
        return Tokens.pair(tokens.access(users.findById(session.userId).orElseThrow()), refreshToken);
    }

    /** Ends the one session the token names. With no token there is nothing to end. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        // Logging out never fails: an expired, unknown or already-ended token leaves nothing to end.
        sessions.lockByTokenDigest(digests.of(Tokens.REFRESH, refreshToken))
                .filter(session -> session.revokedAt == null)
                .ifPresent(session -> session.revokedAt = Instant.now());
    }

    // Known, unexpired and owned by an active user. Whether it was revoked is the caller's question.
    private RefreshSession usableSession(String refreshToken) {
        RefreshSession session = sessions.lockByTokenDigest(digests.of(Tokens.REFRESH, refreshToken)).orElse(null);
        if (session == null || !session.expiresAt.isAfter(Instant.now())
                || users.findById(session.userId).filter(owner -> owner.active).isEmpty()) {
            throw ApiException.invalid(BAD_TOKEN);
        }
        return session;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(Long userId) {
        User user = users.findById(userId).orElseThrow(ApiException::notFound);
        Map<String, Object> body = view(user);
        body.put("active_child_id", user.activeChildId);
        return body;
    }

    /** A null argument leaves that field as it is. An empty one clears it. */
    @Transactional
    public Map<String, Object> updateProfile(Long userId, String name, String nickname, String relation, String ageGroup) {
        User user = users.lockById(userId).filter(found -> found.active).orElseThrow(ApiException::notFound);
        if (name != null) {
            user.name = name;
        }
        if (nickname != null) {
            user.nickname = nickname;
        }
        if (relation != null) {
            user.relation = relation.isEmpty() ? null : relation;
        }
        if (ageGroup != null) {
            user.ageGroup = ageGroup.isEmpty() ? null : ageGroup;
        }
        return view(user);
    }

    /**
     * The member leaves. The row stays, because orders, refund requests and the download record
     * point at it, but nothing personal is left in it and nothing opens it again. What the member
     * entered about a child, the cart, favourites and provider links are destroyed at once.
     * A member who ever ordered is remembered apart (withdrawn_members) for as long as the trade
     * records must be kept; one who never ordered leaves nothing.
     */
    @Transactional
    public void withdraw(Long userId) {
        User user = users.lockById(userId).orElseThrow(ApiException::notFound);
        if (user.withdrawnAt != null) {
            return; // The same request sent twice: the first one did it.
        }
        if (properties.adminEmails().contains(user.email)) {
            throw new ApiException(409, "INVALID_STATE", "관리자 계정은 탈퇴할 수 없습니다. 관리자 목록에서 먼저 빼야 합니다.");
        }
        // Money that is still moving needs its owner: a payment being confirmed, one waiting for a
        // person's look, a refund that was asked for and not yet decided.
        long moving = jdbc.sql("select (select count(*) from orders where user_id = ? and (status = 'review'"
                        + " or (status = 'pending' and confirm_started_at is not null)))"
                        + " + (select count(*) from refund_requests where user_id = ? and status = 'requested')")
                .params(userId, userId).query(Long.class).single();
        if (moving > 0) {
            throw new ApiException(409, "INVALID_STATE", "진행 중인 결제나 환불 요청이 끝난 뒤에 탈퇴할 수 있습니다.");
        }
        jdbc.sql("update orders set status = 'cancelled' where user_id = ? and status = 'pending'").param(userId).update();

        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        // Only where money moved. A free order or one that was never paid is no trade to keep a name for.
        boolean traded = jdbc.sql("select count(*) from orders where user_id = ? and amount > 0"
                + " and status in ('paid', 'refunded')").param(userId).query(Long.class).single() > 0;
        if (traded) {
            jdbc.sql("insert into withdrawn_members (user_id, email, name, phone, withdrawn_at, purge_after)"
                            + " values (?, ?, ?, ?, ?, ?)")
                    .params(userId, user.email, user.name, user.phone, now, now.plusYears(tradeRecordYears)).update();
        } else {
            // No trade to prove: where the free files went is nobody's business any more.
            jdbc.sql("update pdf_downloads set ip_address = null, user_agent = null where user_id = ?").param(userId).update();
        }
        for (String table : new String[]{"children", "cart_items", "favorites", "social_accounts"}) {
            jdbc.sql("delete from " + table + " where user_id = ?").param(userId).update();
        }
        jdbc.sql("delete from email_verifications where email = ?").param(user.email).update();
        sessions.revokeAll(userId, Instant.now());

        // The address is free for a new sign-up at once; this one can never be typed or mailed.
        user.email = "withdrawn-" + userId + "@withdrawn.invalid";
        user.name = "";
        user.nickname = "";
        user.phone = "";
        user.relation = null;
        user.ageGroup = null;
        user.agreements = "{}";
        user.activeChildId = null;
        user.passwordHash = encoder.encode(Digests.randomToken());
        user.tokenVersion++;
        user.active = false;
        user.lastLoginAt = null;
        user.withdrawnAt = Instant.now();
    }

    /** Nightly: who a withdrawn buyer was is destroyed once the trade records no longer need it. */
    @Transactional
    public void purgeWithdrawn() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.sql("update pdf_downloads set ip_address = null, user_agent = null where user_id in"
                + " (select user_id from withdrawn_members where purge_after < ?)").param(now).update();
        // What the buyer and the admin wrote in their own words can name people.
        jdbc.sql("update refund_requests set reason = '', admin_note = null where user_id in"
                + " (select user_id from withdrawn_members where purge_after < ?)").param(now).update();
        jdbc.sql("delete from withdrawn_members where purge_after < ?").param(now).update();
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.lockById(userId).orElseThrow(ApiException::notFound);
        boolean fits = currentPassword.getBytes(StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BYTES;
        if (!fits || !encoder.matches(currentPassword, user.passwordHash)) {
            throw ApiException.invalid("현재 비밀번호를 확인해 주세요.");
        }
        setPassword(user, newPassword);
    }

    // Every session ends and every access token issued so far stops working.
    private void setPassword(User user, String password) {
        String problem = policy.problem(password, user.email);
        if (problem != null) {
            throw ApiException.reason("new_password", problem);
        }
        user.passwordHash = encoder.encode(password);
        user.tokenVersion++;
        sessions.revokeAll(user.id, Instant.now());
    }

    /**
     * Mails a reset link. The answer is the same whether or not the email is registered and
     * whether or not the mail went out, so the endpoint tells nobody who has an account.
     */
    public void requestPasswordReset(String email) {
        String page = properties.passwordResetFrontendUrl();
        if (!safeFrontendUrl(page)) {
            // Checked before the lookup: a missing setting must not reveal registration either.
            log.error("password_reset_frontend_url_invalid");
            throw new ApiException(500, "INTERNAL_SERVER_ERROR", "비밀번호 재설정 메일 설정을 확인해 주세요.");
        }
        User user = users.findByEmail(email).filter(found -> found.active).orElse(null);
        if (user == null) {
            return;
        }
        int seconds = properties.auth().passwordResetTtlSeconds();
        String payload = user.id + ":" + (Instant.now().getEpochSecond() + seconds);
        // Signed over the current password hash: once the password changes the link is dead.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + digests.of(RESET, payload + ":" + user.passwordHash);
        // After '#': a fragment is not sent to the frontend's server or in a Referer header.
        String link = page + "#reset_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            mailer.send(user.email, "YIPPEE CLUB 비밀번호 재설정", RESET_TEXT.formatted(link, seconds),
                    RESET_HTML.formatted(HtmlUtils.htmlEscape(link), seconds, HtmlUtils.htmlEscape(link),
                            HtmlUtils.htmlEscape(link)));
        } catch (Exception error) {
            // The exception text can hold the link or mail credentials: log its kind only.
            log.error("password_reset_email_send_failed {}", error.getClass().getName());
        }
    }

    @Transactional
    public void confirmPasswordReset(String resetToken, String newPassword) {
        User user = resetOwner(resetToken);
        setPassword(user, newPassword);
        // Whoever resets has the mailbox. A sign-in door opened on an address its provider did not
        // vouch for may be somebody else's, so it goes; the owner signs in with the new password.
        removeUnprovenLinks(user);
    }

    private User resetOwner(String token) {
        try {
            int dot = token.lastIndexOf('.');
            String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            String[] parts = payload.split(":");
            User user = users.lockById(Long.parseLong(parts[0])).filter(found -> found.active).orElse(null);
            if (parts.length == 2 && user != null && Long.parseLong(parts[1]) > Instant.now().getEpochSecond()
                    && Digests.same(token.substring(dot + 1), digests.of(RESET, payload + ":" + user.passwordHash))) {
                return user;
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            // Falls through: a broken token gets the same answer as a wrong one.
        }
        throw ApiException.invalid(BAD_RESET);
    }

    /** A fixed https page of ours: no credentials, query, fragment, whitespace or backslash. */
    public static boolean safeFrontendUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        for (char letter : url.toCharArray()) {
            if (Character.isWhitespace(letter) || letter < 32 || letter == 127
                    || letter == '\\' || letter == '?' || letter == '#') {
                return false;
            }
        }
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static Map<String, Object> view(User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.id);
        body.put("email", user.email);
        body.put("phone", user.phone);
        body.put("name", user.name);
        body.put("nickname", user.nickname);
        body.put("relation", user.relation);
        body.put("age_group", user.ageGroup);
        // False until the parent-info screen was filled in (a nickname and a relation; the age group
        // is optional). An account exists before that screen, so the app sends such a member back to it.
        body.put("profile_completed", !user.nickname.isEmpty() && user.relation != null);
        return body;
    }

    private static final String RESET_TEXT = """
            YIPPEE CLUB 비밀번호 재설정

            아래 링크를 열어 새 비밀번호를 입력해 주세요.
            %s

            이 링크는 요청 시점부터 %d초 동안 유효하며, 비밀번호 변경 후에는 다시 사용할 수 없습니다.
            본인이 요청하지 않았다면 이 이메일을 무시해 주세요. 링크와 토큰을 다른 사람에게 공유하지 마세요.
            """;

    private static final String RESET_HTML = """
            <!doctype html>
            <html lang="ko">
            <head><meta charset="utf-8"><title>YIPPEE CLUB 비밀번호 재설정</title></head>
            <body style="margin:0;padding:32px 16px;background:#f6f7f9;color:#202633;font-family:Arial,sans-serif;line-height:1.7;">
              <div style="max-width:520px;margin:0 auto;padding:32px;background:#ffffff;border-radius:12px;">
                <p style="margin:0;color:#516450;font-weight:bold;">YIPPEE CLUB</p>
                <h1 style="font-size:24px;">비밀번호를 재설정해 주세요</h1>
                <p>아래 버튼을 눌러 새 비밀번호를 입력해 주세요.</p>
                <p style="margin:28px 0;">
                  <a href="%s" style="display:inline-block;padding:14px 24px;border-radius:8px;background:#355d45;color:#ffffff;text-decoration:none;font-weight:bold;">비밀번호 재설정하기</a>
                </p>
                <p>이 링크는 요청 시점부터 %d초 동안 유효하며, 비밀번호 변경 후에는 다시 사용할 수 없습니다.</p>
                <p>본인이 요청하지 않았다면 이 이메일을 무시해 주세요. 링크와 토큰을 다른 사람에게 공유하지 마세요.</p>
                <p style="font-size:13px;">버튼이 열리지 않으면 아래 주소를 복사해 브라우저에서 열어 주세요.</p>
                <p style="font-size:12px;overflow-wrap:anywhere;"><a href="%s">%s</a></p>
              </div>
            </body>
            </html>
            """;
}
