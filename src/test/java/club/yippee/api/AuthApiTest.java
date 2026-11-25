package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import club.yippee.api.account.AccountService;

/** The twelve sign-in and profile operations, held to the behaviour of the Django server's tests. */
class AuthApiTest extends ApiTestBase {

    private static final String AUTH = "/api/v1/auth";
    private static final String ME = "/api/v1/users/me";
    private static final String EMAIL = "parent@example.com";
    private static final String BAD_INPUT = "VALIDATION_ERROR";

    private static String login(String email, String password) {
        return q("{'email':'%s','password':'%s'}".formatted(email, password));
    }

    private static String refreshBody(String token) {
        return q("{'refresh_token':'%s'}".formatted(token));
    }

    private String[] sendCode(String identifier) throws Exception {
        ResultActions sent = post(AUTH + "/verification/send", null,
                q("{'identifier':'%s','purpose':'signup'}".formatted(identifier))).andExpect(status().isOk());
        String code = lastMailText().replaceAll("(?s).*인증번호: (\\d{6}).*", "$1");
        return new String[]{read(sent, "$.verification_id"), code};
    }

    private static String confirmBody(String id, String code) {
        return q("{'verification_id':'%s','code':'%s'}".formatted(id, code));
    }

    // --- email code and signup ---

    @Test
    void emailCodeThenSignupStoresOnlyHashes() throws Exception {
        String[] challenge = sendCode(" Parent@Example.COM ");
        ResultActions confirmed = post(AUTH + "/verification/confirm", null, confirmBody(challenge[0], challenge[1]))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        String proof = read(confirmed, "$.verification_token");
        ResultActions created = post(AUTH + "/signup", null, signupBody(EMAIL, proof, PASSWORD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(3));
        Number userId = read(created, "$.user_id");
        String refresh = read(created, "$.refresh_token");

        String hash = jdbc.sql("select password_hash from users where id = ?").param(userId).query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(count("select count(*) from email_verifications where id = ? and code_digest = ''"
                + " and consumed_at is not null and token_digest <> ?", challenge[0], proof)).isEqualTo(1);
        assertThat(count("select count(*) from refresh_sessions where user_id = ?", userId)).isEqualTo(1);
        assertThat(count("select count(*) from refresh_sessions where token_digest = ?", refresh)).isZero();
    }

    @Test
    void signupNeedsALiveVerifiedProofForThatEmail() throws Exception {
        expectError(post(AUTH + "/signup", null, signupBody(EMAIL, proof("another@example.com"), PASSWORD)), 400, BAD_INPUT);
        expectError(post(AUTH + "/signup", null, signupBody(EMAIL, "never-issued", PASSWORD)), 400, BAD_INPUT);
        for (String spoil : List.of("verified_at = null", "purpose = 'password_reset'", "token_expires_at = created_at")) {
            String token = proof(EMAIL);
            jdbc.sql("update email_verifications set " + spoil).update();
            expectError(post(AUTH + "/signup", null, signupBody(EMAIL, token, PASSWORD)), 400, BAD_INPUT);
        }
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void signupFieldsAreStrict() throws Exception {
        String token = proof(EMAIL);
        String full = "'identifier':'" + EMAIL + "','verification_token':'" + token + "','password':'" + PASSWORD + "','agreements':{}";
        for (String field : List.of("identifier", "verification_token", "password", "agreements")) {
            String without = full.replaceAll("'" + field + "':('[^']*'|\\{\\}),?", "").replaceAll(",$", "");
            expectError(post(AUTH + "/signup", null, q("{" + without + "}")), 400, BAD_INPUT);
            expectError(post(AUTH + "/signup", null, q("{" + without + (without.isEmpty() ? "" : ",") + "'" + field + "':null}")), 400, BAD_INPUT);
        }
        expectError(post(AUTH + "/signup", null, q("{" + full + ",'is_staff':true}")), 400, BAD_INPUT);
        expectError(post(AUTH + "/signup", null, q("{" + full.replace("{}", "[]") + "}")), 400, BAD_INPUT);
        expectError(post(AUTH + "/signup", null, "[]"), 400, BAD_INPUT);
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void theRequiredConsentsMustBeGiven() throws Exception {
        for (String consent : List.of("{}", "{'terms':false}", "{'terms':'yes'}", "{'privacy':true}")) {
            String body = signupBody(EMAIL, proof(EMAIL), PASSWORD).replace(q("{'terms':true}"), q(consent));
            ResultActions refused = post(AUTH + "/signup", null, body);
            expectError(refused, 400, BAD_INPUT).andExpect(jsonPath("$.details.agreements").isArray());
        }
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void aProofWorksOnceAndAnEmailRegistersOnce() throws Exception {
        String body = signupBody(EMAIL, proof(EMAIL), PASSWORD);
        post(AUTH + "/signup", null, body).andExpect(status().isCreated());
        expectError(post(AUTH + "/signup", null, body), 409, "INVALID_STATE");
        expectError(post(AUTH + "/signup", null, signupBody("Parent@Example.COM", proof(EMAIL), PASSWORD)), 409, "ALREADY_EXISTS");
        assertThat(count("select count(*) from users")).isEqualTo(1);
    }

    @Test
    void weakPasswordsAreRefused() throws Exception {
        for (String weak : List.of("short1", "onlyletterslong", "123456789", "password123", "parent2example")) {
            expectError(post(AUTH + "/signup", null, signupBody(EMAIL, proof(EMAIL), weak)), 400, BAD_INPUT)
                    .andExpect(jsonPath("$.details.password[0]").isString());
        }
        String numeric = signupBody(EMAIL, proof(EMAIL), "x").replace("\"x\"", "12345678");
        expectError(post(AUTH + "/signup", null, numeric), 400, BAD_INPUT).andExpect(jsonPath("$.details.password").isArray());
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void passwordWhitespaceIsPartOfThePassword() throws Exception {
        String spaced = " SecurePass!829341 ";
        post(AUTH + "/signup", null, signupBody(EMAIL, proof(EMAIL), spaced)).andExpect(status().isCreated());
        post(AUTH + "/login", null, login(EMAIL, spaced)).andExpect(status().isOk());
        expectError(post(AUTH + "/login", null, login(EMAIL, spaced.strip())), 400, BAD_INPUT);
    }

    // --- login, refresh, logout ---

    @Test
    void loginContractAndOneAnswerForEveryFailure() throws Exception {
        Session account = signUp(EMAIL);
        post(AUTH + "/login", null, login("PARENT@EXAMPLE.COM", PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.access_token").isString()).andExpect(jsonPath("$.refresh_token").isString())
                .andExpect(jsonPath("$.user.length()").value(5))
                .andExpect(jsonPath("$.user.id").value(account.userId())).andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.phone").value("")).andExpect(jsonPath("$.user.nickname").value(""));
        assertThat(count("select count(*) from users where last_login_at is not null")).isEqualTo(1);

        String wrong = read(post(AUTH + "/login", null, login(EMAIL, "incorrect")), "$.message");
        String unknown = read(post(AUTH + "/login", null, login("unknown@example.com", PASSWORD)), "$.message");
        jdbc.sql("update users set is_active = false").update();
        ResultActions refused = post(AUTH + "/login", null, login(EMAIL, PASSWORD));
        expectError(refused, 400, BAD_INPUT);
        String inactive = read(refused, "$.message");
        assertThat(List.of(wrong, unknown, inactive)).containsOnly("이메일 또는 비밀번호를 확인해 주세요.");
        expectError(post(AUTH + "/login", null, q("{'email':'" + EMAIL + "','password':'" + PASSWORD + "','remember':true}")), 400, BAD_INPUT);
    }

    @Test
    void refreshGivesANewAccessTokenAndKeepsTheRefreshToken() throws Exception {
        Session account = signUp(EMAIL);
        get(ME, account.access()).andExpect(status().isOk());
        // Refresh and logout are not capped per address: a whole daycare can share one.
        seedLimit("refresh:ip", "127.0.0.1", 1000);
        seedLimit("logout:ip", "127.0.0.1", 1000);
        post(AUTH + "/logout", null, "{}").andExpect(status().isOk());
        ResultActions refreshed = post(AUTH + "/token/refresh", null, refreshBody(account.refresh()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.refresh_token").value(account.refresh()));
        String access = read(refreshed, "$.access_token");
        get(ME, access).andExpect(status().isOk());

        for (String notARefreshToken : List.of("invalid", account.access())) {
            expectError(post(AUTH + "/token/refresh", null, refreshBody(notARefreshToken)), 400, BAD_INPUT);
        }
        jdbc.sql("update refresh_sessions set expires_at = ?").param(utc(Instant.now().minusSeconds(1))).update();
        expectError(post(AUTH + "/token/refresh", null, refreshBody(account.refresh())), 400, BAD_INPUT);
    }

    @Test
    void aDeactivatedUsersTokensStopWorking() throws Exception {
        Session account = signUp(EMAIL);
        jdbc.sql("update users set is_active = false").update();
        expectError(post(AUTH + "/token/refresh", null, refreshBody(account.refresh())), 400, BAD_INPUT);
        expectError(get(ME, account.access()), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void logoutEndsOnlyTheSessionItIsGiven() throws Exception {
        Session first = signUp(EMAIL);
        String second = read(post(AUTH + "/login", null, login(EMAIL, PASSWORD)), "$.refresh_token");
        post(AUTH + "/logout", null, refreshBody(first.refresh())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("처리되었습니다."));
        post(AUTH + "/logout", null, refreshBody(first.refresh())).andExpect(status().isOk());
        expectError(post(AUTH + "/token/refresh", null, refreshBody(first.refresh())), 400, BAD_INPUT);
        post(AUTH + "/token/refresh", null, refreshBody(second)).andExpect(status().isOk());

        post(AUTH + "/logout", null, "{}").andExpect(status().isOk());
        post(AUTH + "/logout", null, q("{'refresh_token':null}")).andExpect(status().isOk());
        // Logging out never fails, whatever the token: unknown, expired or already ended.
        post(AUTH + "/logout", null, refreshBody("invalid")).andExpect(status().isOk());
        jdbc.sql("update refresh_sessions set expires_at = ?").param(utc(Instant.now().minusSeconds(1))).update();
        post(AUTH + "/logout", null, refreshBody(second)).andExpect(status().isOk());
        expectError(post(AUTH + "/logout", null, q("{'refresh_token':5}")), 400, BAD_INPUT);
    }

    // --- profile ---

    @Test
    void profileNeedsAValidAccessToken() throws Exception {
        Session account = signUp(EMAIL);
        expectError(get(ME, null), 401, "AUTHENTICATION_REQUIRED");
        expectError(get(ME, "invalid"), 401, "AUTHENTICATION_REQUIRED");
        expectError(get(ME, account.refresh()), 401, "AUTHENTICATION_REQUIRED");
        expectError(patch(ME, "invalid", q("{'name':'private'}")), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void profileReadUpdateAndClear() throws Exception {
        Session account = signUp(EMAIL);
        get(ME, account.access()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.id").value(account.userId())).andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.phone").value("")).andExpect(jsonPath("$.name").value(""))
                .andExpect(jsonPath("$.nickname").value("")).andExpect(jsonPath("$.active_child_id").isEmpty());

        patch(ME, account.access(), q("{'name':' 부모 ','nickname':'닉네임'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.name").value("부모")).andExpect(jsonPath("$.nickname").value("닉네임"));
        patch(ME, account.access(), q("{'name':'보호자'}")).andExpect(jsonPath("$.nickname").value("닉네임"));
        get(ME, account.access()).andExpect(jsonPath("$.name").value("보호자"));
        patch(ME, account.access(), q("{'name':null,'nickname':null}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("")).andExpect(jsonPath("$.nickname").value(""));
        patch(ME, account.access(), "{}").andExpect(status().isOk());

        for (String forbidden : List.of("'id':99", "'user_id':99", "'email':'other@example.com'", "'phone':'01000000000'",
                "'is_staff':true", "'password':'attack'", "'name':123", "'name':'" + "가".repeat(101) + "'")) {
            expectError(patch(ME, account.access(), q("{" + forbidden + "}")), 400, BAD_INPUT);
        }
        get(ME, account.access()).andExpect(jsonPath("$.email").value(EMAIL)).andExpect(jsonPath("$.name").value(""));
        expectError(get("/api/v1/users/" + account.userId(), account.access()), 404, "NOT_FOUND");
    }

    // --- passwords ---

    @Test
    void passwordChangeEndsEveryOldSession() throws Exception {
        Session account = signUp(EMAIL);
        String changed = "NewAccountPwd!382927";
        expectError(patch(ME + "/password", account.access(), q("{'current_password':'wrong','new_password':'" + changed + "'}")), 400, BAD_INPUT);
        ResultActions weak = patch(ME + "/password", account.access(), q("{'current_password':'" + PASSWORD + "','new_password':'short'}"));
        expectError(weak, 400, BAD_INPUT);
        // The app is told which field and why, in the server's own words.
        weak.andExpect(jsonPath("$.details.new_password[0]").value("영문과 숫자를 포함해 8자 이상 입력해 주세요."));
        patch(ME + "/password", account.access(), q("{'current_password':'" + PASSWORD + "','new_password':'password123'}"))
                .andExpect(jsonPath("$.details.new_password[0]").value("너무 흔한 비밀번호입니다."));
        post(AUTH + "/login", null, login(EMAIL, PASSWORD)).andExpect(status().isOk());

        patch(ME + "/password", account.access(), q("{'current_password':'" + PASSWORD + "','new_password':'" + changed + "'}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("처리되었습니다."));
        expectError(get(ME, account.access()), 401, "AUTHENTICATION_REQUIRED");
        expectError(post(AUTH + "/token/refresh", null, refreshBody(account.refresh())), 400, BAD_INPUT);
        expectError(post(AUTH + "/login", null, login(EMAIL, PASSWORD)), 400, BAD_INPUT);
        post(AUTH + "/login", null, login(EMAIL, changed)).andExpect(status().isOk());
    }

    private String resetToken() throws Exception {
        String link = lastMailText().lines().filter(line -> line.startsWith("https://")).findFirst().orElseThrow();
        assertThat(link).startsWith("https://frontend.example.com/password/reset#reset_token=").doesNotContain("?");
        return java.net.URLDecoder.decode(link.substring(link.indexOf('=') + 1), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String resetBody(String token, String password) {
        return q("{'reset_token':'%s','new_password':'%s'}".formatted(token, password));
    }

    @Test
    void passwordResetLinkWorksOnceAndEndsOldSessions() throws Exception {
        Session account = signUp(EMAIL);
        post(AUTH + "/password/reset-request", null, q("{'email':'" + EMAIL + "'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$.message").value("처리되었습니다."));
        String token = resetToken();
        String renewed = "ResetSecurePwd!823948";

        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0");
        expectError(post(AUTH + "/password/reset-confirm", null, resetBody(tampered, renewed)), 400, BAD_INPUT);
        expectError(post(AUTH + "/password/reset-confirm", null, resetBody("not.a.token", renewed)), 400, BAD_INPUT);
        expectError(post(AUTH + "/password/reset-confirm", null, resetBody(token, "weak")), 400, BAD_INPUT);
        post(AUTH + "/login", null, login(EMAIL, PASSWORD)).andExpect(status().isOk());

        post(AUTH + "/password/reset-confirm", null, resetBody(token, renewed)).andExpect(status().isOk());
        expectError(post(AUTH + "/password/reset-confirm", null, resetBody(token, "Another" + renewed)), 400, BAD_INPUT);
        expectError(post(AUTH + "/token/refresh", null, refreshBody(account.refresh())), 400, BAD_INPUT);
        expectError(get(ME, account.access()), 401, "AUTHENTICATION_REQUIRED");
        post(AUTH + "/login", null, login(EMAIL, renewed)).andExpect(status().isOk());
    }

    @Test
    void passwordResetRequestTellsNobodyWhoIsRegistered() throws Exception {
        post(AUTH + "/password/reset-request", null, q("{'email':'unknown@example.com'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("처리되었습니다."));
        verify(mailer, never()).send(anyString(), anyString(), anyString(), any());

        signUp(EMAIL);
        doThrow(new MessagingException("smtp-secret")).when(mailer).send(anyString(), anyString(), anyString(), any());
        post(AUTH + "/password/reset-request", null, q("{'email':'" + EMAIL + "'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("처리되었습니다."));
        expectError(post(AUTH + "/password/reset-request", null,
                q("{'email':'" + EMAIL + "','redirect_url':'https://other.example/reset'}")), 400, BAD_INPUT);
    }

    @Test
    void resetPageMustBeAFixedHttpsAddress() {
        assertThat(AccountService.safeFrontendUrl("https://frontend.example.com/password/reset")).isTrue();
        for (String unsafe : List.of("", "http://frontend.example.com/reset", "//frontend.example.com/reset",
                "https://user:secret@frontend.example.com/reset", "https://frontend.example.com/reset?next=https://other.example",
                "https://frontend.example.com/reset#existing", "https://frontend.example.com/\\other",
                "https://frontend.example.com/reset\r\n", "javascript:alert(1)")) {
            assertThat(AccountService.safeFrontendUrl(unsafe)).as(unsafe).isFalse();
        }
    }

    // --- rate limits ---

    @Test
    void addressAndEmailLimitsAnswer429() throws Exception {
        seedLimit("login:ip", "127.0.0.1", 999);
        expectError(post(AUTH + "/login", null, login(EMAIL, PASSWORD)), 400, BAD_INPUT);
        expectError(post(AUTH + "/login", null, login(EMAIL, PASSWORD)), 429, "RATE_LIMITED");

        seedLimit("password-reset-request:email", "unknown@example.com", 999);
        post(AUTH + "/password/reset-request", null, q("{'email':'unknown@example.com'}")).andExpect(status().isOk());
        expectError(post(AUTH + "/password/reset-request", null, q("{'email':'unknown@example.com'}")), 429, "RATE_LIMITED");
        post(AUTH + "/password/reset-request", null, q("{'email':'someone@example.com'}")).andExpect(status().isOk());
    }

    private ResultActions loginFrom(String address, String password) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
                .content(login(EMAIL, password)).with(request -> { request.setRemoteAddr(address); return request; }));
    }

    @Test
    void someoneElsesWrongGuessesDoNotLockTheOwnerOut() throws Exception {
        signUp(EMAIL);
        // Ten wrong guesses from one address use up that address's allowance for this account...
        seedLimit("login:email", "198.51.100.1|" + EMAIL, 990);
        for (int guess = 0; guess < 10; guess++) {
            expectError(loginFrom("198.51.100.1", "wrong-" + guess), 400, BAD_INPUT);
        }
        expectError(loginFrom("198.51.100.1", PASSWORD), 429, "RATE_LIMITED");
        // ...while the owner, signing in from elsewhere, is not affected.
        loginFrom("203.0.113.99", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void forwardedHeadersAreIgnoredUnlessAProxyIsConfigured() throws Exception {
        seedLimit("identifier-check:ip", "192.0.2.10", 999);
        String body = q("{'identifier':'" + EMAIL + "'}");
        mvc.perform(MockMvcRequestBuilders.post(AUTH + "/identifiers/check").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                .header("X-Forwarded-For", "198.51.100.10")).andExpect(status().isOk());
        expectError(mvc.perform(MockMvcRequestBuilders.post(AUTH + "/identifiers/check").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                .header("X-Forwarded-For", "198.51.100.20").header("X-Real-IP", "198.51.100.21")), 429, "RATE_LIMITED");
        mvc.perform(MockMvcRequestBuilders.post(AUTH + "/identifiers/check").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> { request.setRemoteAddr("192.0.2.11"); return request; })
                .header("X-Forwarded-For", "198.51.100.10")).andExpect(status().isOk());
    }

    // --- the emailed code ---

    @Test
    void identifierCheckAndSendKnowWhoIsRegistered() throws Exception {
        post(AUTH + "/identifiers/check", null, q("{'identifier':'" + EMAIL + "'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false)).andExpect(jsonPath("$.identifier_type").value("email"));
        signUp(EMAIL);
        post(AUTH + "/identifiers/check", null, q("{'identifier':'Parent@Example.com'}")).andExpect(jsonPath("$.registered").value(true));
        expectError(post(AUTH + "/verification/send", null, q("{'identifier':'" + EMAIL + "','purpose':'signup'}")), 409, "ALREADY_EXISTS");
        for (String bad : List.of("{'identifier':'01012345678','purpose':'signup'}",
                "{'identifier':'new@example.com','purpose':'password_reset'}", "{'identifier':'new@example.com'}")) {
            expectError(post(AUTH + "/verification/send", null, q(bad)), 400, BAD_INPUT);
        }
        verify(mailer, never()).send(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aWrongCodeIsCountedAndTheCountSurvivesTheError() throws Exception {
        String[] challenge = sendCode(EMAIL);
        String wrong = challenge[1].equals("000000") ? "000001" : "000000";
        expectError(post(AUTH + "/verification/confirm", null, confirmBody(challenge[0], wrong)), 400, BAD_INPUT);
        assertThat(count("select attempts from email_verifications where id = ?", challenge[0])).isEqualTo(1);

        jdbc.sql("update email_verifications set attempts = 5").update();
        expectError(post(AUTH + "/verification/confirm", null, confirmBody(challenge[0], challenge[1])), 429, "RATE_LIMITED");
    }

    @Test
    void aCodeExpiresAndConfirmsOnlyOnce() throws Exception {
        String[] expired = sendCode(EMAIL);
        jdbc.sql("update email_verifications set expires_at = ?").param(utc(Instant.now().minusSeconds(1))).update();
        expectError(post(AUTH + "/verification/confirm", null, confirmBody(expired[0], expired[1])), 400, BAD_INPUT);

        String[] challenge = sendCode("second@example.com");
        post(AUTH + "/verification/confirm", null, confirmBody(challenge[0], challenge[1])).andExpect(status().isOk());
        expectError(post(AUTH + "/verification/confirm", null, confirmBody(challenge[0], challenge[1])), 409, "INVALID_STATE");
        expectError(post(AUTH + "/verification/confirm", null, confirmBody("ver_unknown", "123456")), 400, BAD_INPUT);
    }

    @Test
    void theCodeMustBeSixDigitsSentAsAString() throws Exception {
        for (String code : List.of("'12345'", "'1234567'", "' 123456'", "'abcdef'", "123456", "null")) {
            ResultActions refused = post(AUTH + "/verification/confirm", null, q("{'verification_id':'ver_x','code':" + code + "}"));
            expectError(refused, 400, BAD_INPUT);
            refused.andExpect(jsonPath("$.details.code").isArray());
        }
    }

    @Test
    void resendWaitsForTheCooldown() throws Exception {
        sendCode(EMAIL);
        expectError(post(AUTH + "/verification/send", null, q("{'identifier':'" + EMAIL + "','purpose':'signup'}")), 429, "RATE_LIMITED");
    }

    @Test
    void whenTheMailFailsNoRequestIsKeptAndNothingLeaks() throws Exception {
        doThrow(new MessagingException("secret-mail-body")).when(mailer).send(anyString(), anyString(), anyString(), any());
        ResultActions failed = post(AUTH + "/verification/send", null, q("{'identifier':'" + EMAIL + "','purpose':'signup'}"));
        expectError(failed, 503, "SERVICE_UNAVAILABLE");
        assertThat(failed.andReturn().getResponse().getContentAsString()).doesNotContain("secret-mail-body");
        assertThat(count("select count(*) from email_verifications")).isZero();
    }

    // --- addresses ---

    @Test
    void methodsAndAddressesFollowTheContract() throws Exception {
        expectError(get(AUTH + "/signup", null), 405, "INVALID_STATE");
        expectError(post(AUTH + "/signup/", null, "{}"), 404, "NOT_FOUND");
        expectError(get(ME + "/password", null), 401, "AUTHENTICATION_REQUIRED");
    }
}
