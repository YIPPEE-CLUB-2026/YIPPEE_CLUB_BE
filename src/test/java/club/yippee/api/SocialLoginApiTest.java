package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sign-in through Kakao, Naver and Google, against a local stand-in for all three. Nothing leaves
 * this machine. That an unconfigured provider answers 404 is in CommonApiTest.
 */
class SocialLoginApiTest extends ApiTestBase {

    private static final String SOCIAL = "/api/v1/auth/social/";
    private static final String KAKAO_OK = "{'id':12345,'kakao_account':{'email':'Parent@Example.com',"
            + "'is_email_valid':true,'is_email_verified':true}}";

    /** What the stand-in answers, by path: {status, body}. */
    private static final Map<String, String[]> ANSWERS = new ConcurrentHashMap<>();
    /** What it was sent: "path | Authorization header | body". */
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer PROVIDERS = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                SEEN.add(path + " | " + exchange.getRequestHeaders().getFirst("Authorization") + " | "
                        + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String[] answer = ANSWERS.getOrDefault(path, new String[]{"404", "{}"});
                byte[] body = answer[1].getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(Integer.parseInt(answer[0]), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + PROVIDERS.getAddress().getPort();
        for (String provider : List.of("kakao", "naver", "google")) {
            registry.add("app.social." + provider + ".client-id", () -> provider + "-id");
            registry.add("app.social." + provider + ".client-secret", () -> provider + "-secret");
            registry.add("app.social." + provider + ".token-url", () -> base + "/" + provider + "/token");
            registry.add("app.social." + provider + ".profile-url", () -> base + "/" + provider + "/me");
        }
    }

    @BeforeEach
    void everyProviderAcceptsTheCode() {
        SEEN.clear();
        ANSWERS.clear();
        for (String provider : List.of("kakao", "naver", "google")) {
            ANSWERS.put("/" + provider + "/token", new String[]{"200", "{\"access_token\":\"" + provider + "-token\"}"});
        }
    }

    private static void profile(String provider, String json) {
        ANSWERS.put("/" + provider + "/me", new String[]{"200", q(json)});
    }

    private ResultActions signIn(String provider, String extra) throws Exception {
        return post(SOCIAL + provider, null,
                q("{'authorization_code':'code-1','redirect_uri':'http://localhost:3000/cb','state':'st-1'" + extra + "}"));
    }

    private static final String AGREED = ",'agreements':{'terms':true}";

    @Test
    void aFirstKakaoSignInMakesTheAccountAndTheNextOneOpensIt() throws Exception {
        profile("kakao", KAKAO_OK);
        ResultActions first = signIn("kakao", AGREED).andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_new_user").value(true))
                .andExpect(jsonPath("$.refresh_token").isString())
                .andExpect(jsonPath("$.user.email").value("parent@example.com"));
        get("/api/v1/users/me", read(first, "$.access_token")).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("parent@example.com"));

        // The code is exchanged with our key and the given redirect address; the profile is read with the provider's token.
        assertThat(SEEN.get(0)).startsWith("/kakao/token | null | ").contains("grant_type=authorization_code")
                .contains("code=code-1").contains("client_id=kakao-id").contains("client_secret=kakao-secret")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Fcb");
        assertThat(SEEN.get(1)).startsWith("/kakao/me | Bearer kakao-token | ");

        // The second time no consent is asked: the account exists. The provider's number names it, not the email.
        profile("kakao", "{'id':12345,'kakao_account':{'email':'moved@example.com','is_email_valid':true,'is_email_verified':true}}");
        signIn("kakao", "").andExpect(status().isOk()).andExpect(jsonPath("$.is_new_user").value(false))
                .andExpect(jsonPath("$.user.email").value("parent@example.com"));
        assertThat(count("select count(*) from users")).isEqualTo(1);
        assertThat(count("select count(*) from social_accounts where provider = 'kakao' and provider_user_id = '12345'"))
                .isEqualTo(1);
        // The account has no password anyone knows: the email-and-password door stays shut.
        expectError(post("/api/v1/auth/login", null, q("{'email':'parent@example.com','password':''}")), 400, "VALIDATION_ERROR");
    }

    @Test
    void aNewAccountNeedsTheRequiredConsent() throws Exception {
        profile("kakao", KAKAO_OK);
        expectError(signIn("kakao", ""), 409, "AGREEMENTS_REQUIRED");
        expectError(signIn("kakao", ",'agreements':{'terms':false}"), 409, "AGREEMENTS_REQUIRED");
        expectError(signIn("kakao", ",'agreements':'yes'"), 400, "VALIDATION_ERROR");
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void aVerifiedEmailJoinsTheAccountThatAlreadyHasIt() throws Exception {
        Session owner = signUp("parent@gmail.com");
        profile("google", "{'sub':'g-1','email':'Parent@gmail.com','email_verified':true}");
        ResultActions joined = signIn("google", "").andExpect(status().isOk()).andExpect(jsonPath("$.is_new_user").value(false));
        Number id = read(joined, "$.user.id");
        assertThat(id.longValue()).isEqualTo(owner.userId());
        assertThat(count("select count(*) from social_accounts where user_id = ? and email_verified = true", owner.userId()))
                .isEqualTo(1);
        // The owner's password still works.
        post("/api/v1/auth/login", null, q("{'email':'parent@gmail.com','password':'%s'}".formatted(PASSWORD)))
                .andExpect(status().isOk());

        // Google is the authority for gmail.com and for its own hosted domains; for any other address
        // its "verified" is not enough to hand over an account that exists.
        signUp("boss@company.example");
        profile("google", "{'sub':'g-2','email':'boss@company.example','email_verified':true}");
        expectError(signIn("google", ""), 409, "ALREADY_EXISTS");
        profile("google", "{'sub':'g-2','email':'boss@company.example','email_verified':'true','hd':'company.example'}");
        signIn("google", "").andExpect(status().isOk());
        profile("google", "{'sub':'g-3','email':'parent@gmail.com','email_verified':false}");
        expectError(signIn("google", ""), 409, "ALREADY_EXISTS");
    }

    @Test
    void anUnverifiedEmailNeverOpensAnExistingAccountAndAPasswordResetTakesItsDoorAway() throws Exception {
        // Naver says nothing about whether its email was verified.
        signUp("taken@example.com");
        profile("naver", "{'resultcode':'00','message':'success','response':{'id':'nv-abc','email':'taken@example.com'}}");
        expectError(signIn("naver", AGREED), 409, "ALREADY_EXISTS");
        assertThat(count("select count(*) from social_accounts")).isZero();
        assertThat(SEEN.get(0)).contains("state=st-1").contains("client_secret=naver-secret");

        // An address nobody has registered makes a new account.
        profile("naver", "{'resultcode':'00','message':'success','response':{'id':'nv-abc','email':'fresh@example.com'}}");
        signIn("naver", AGREED).andExpect(status().isCreated()).andExpect(jsonPath("$.is_new_user").value(true));
        ResultActions again = signIn("naver", "").andExpect(status().isOk());
        assertThat(count("select count(*) from social_accounts where email_verified = false")).isEqualTo(1);

        // Whoever owns the mailbox resets the password: every session ends, and the door that was
        // opened on an unproven address is gone with them.
        post("/api/v1/auth/password/reset-request", null, q("{'email':'fresh@example.com'}")).andExpect(status().isOk());
        String link = lastMailText().lines().filter(line -> line.startsWith("https://")).findFirst().orElseThrow();
        String token = java.net.URLDecoder.decode(link.substring(link.indexOf("reset_token=") + 12), StandardCharsets.UTF_8);
        post("/api/v1/auth/password/reset-confirm", null,
                q("{'reset_token':'%s','new_password':'%s'}".formatted(token, PASSWORD))).andExpect(status().isOk());
        expectError(get("/api/v1/users/me", read(again, "$.access_token")), 401, "AUTHENTICATION_REQUIRED");
        expectError(signIn("naver", ""), 409, "ALREADY_EXISTS");
        assertThat(count("select count(*) from social_accounts")).isZero();
    }

    // Someone puts another person's address on a Naver account and makes our account for it first.
    // When the owner arrives through a provider that does vouch for the address, the account is the
    // owner's: the unproven door and every session opened through it are gone.
    @Test
    void aVouchedSignInTakesTheAccountBackFromAnUnprovenOne() throws Exception {
        profile("naver", "{'resultcode':'00','message':'success','response':{'id':'nv-squat','email':'parent@example.com'}}");
        ResultActions squatter = signIn("naver", AGREED).andExpect(status().isCreated());
        profile("kakao", KAKAO_OK);
        ResultActions owner = signIn("kakao", "").andExpect(status().isOk()).andExpect(jsonPath("$.is_new_user").value(false));

        expectError(get("/api/v1/users/me", read(squatter, "$.access_token")), 401, "AUTHENTICATION_REQUIRED");
        expectError(post("/api/v1/auth/token/refresh", null,
                q("{'refresh_token':'%s'}".formatted((String) read(squatter, "$.refresh_token")))), 400, "VALIDATION_ERROR");
        expectError(signIn("naver", ""), 409, "ALREADY_EXISTS");
        assertThat(count("select count(*) from social_accounts where email_verified = false")).isZero();
        get("/api/v1/users/me", read(owner, "$.access_token")).andExpect(status().isOk());
        signIn("kakao", "").andExpect(status().isOk());
    }

    // The provider's number is matched to the letter: MySQL's default comparison ignores case, and
    // Naver's numbers are mixed-case text.
    @Test
    void aProvidersNumberIsMatchedToTheLetter() throws Exception {
        profile("naver", "{'resultcode':'00','message':'success','response':{'id':'AbC','email':'one@example.com'}}");
        signIn("naver", AGREED).andExpect(status().isCreated());
        profile("naver", "{'resultcode':'00','message':'success','response':{'id':'aBc','email':'two@example.com'}}");
        ResultActions other = signIn("naver", AGREED);
        assertThat(other.andReturn().getResponse().getContentAsString()).doesNotContain("one@example.com");
        assertThat(other.andReturn().getResponse().getStatus()).isIn(201, 503);
    }

    @Test
    void anAdminAddressIsNeverSignedInThroughAProvider() throws Exception {
        profile("kakao", "{'id':1,'kakao_account':{'email':'ADMIN@example.com','is_email_valid':true,'is_email_verified':true}}");
        expectError(signIn("kakao", AGREED), 403, "PERMISSION_DENIED");
        assertThat(count("select count(*) from users")).isZero();

        // Nor an account that was linked before its address was made an admin's.
        Session admin = signUp("admin@example.com");
        jdbc.sql("insert into social_accounts (user_id, provider, provider_user_id, email_verified, created_at)"
                + " values (?, 'kakao', '1', true, ?)").params(admin.userId(), utc(java.time.Instant.now())).update();
        expectError(signIn("kakao", ""), 403, "PERMISSION_DENIED");

        // An account made on an unproven address may be somebody else's. If that address is later put
        // on the admin list, the account is not an admin until its owner has proven the mailbox
        // (a password reset removes the unproven door).
        get("/api/v1/admin/orders", admin.access()).andExpect(status().isOk());
        jdbc.sql("update social_accounts set email_verified = false").update();
        expectError(get("/api/v1/admin/orders", admin.access()), 403, "PERMISSION_DENIED");
    }

    @Test
    void withoutAUsableEmailThereIsNoAccount() throws Exception {
        for (String profile : List.of("{'id':7}", "{'id':7,'kakao_account':{'email_needs_agreement':true}}",
                // Kakao hands an expired address out partly masked.
                "{'id':7,'kakao_account':{'email':'ka***@kakao.com','is_email_valid':false,'is_email_verified':true}}",
                "{'id':7,'kakao_account':{'email':'not an email','is_email_valid':true,'is_email_verified':true}}")) {
            profile("kakao", profile);
            expectError(signIn("kakao", AGREED), 409, "EMAIL_REQUIRED");
        }
        profile("naver", "{'resultcode':'00','message':'success','response':{'id':'nv-1'}}");
        expectError(signIn("naver", AGREED), 409, "EMAIL_REQUIRED");
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void aClosedAccountStaysClosed() throws Exception {
        profile("kakao", KAKAO_OK);
        signIn("kakao", AGREED).andExpect(status().isCreated());
        jdbc.sql("update users set is_active = false").update();
        expectError(signIn("kakao", ""), 403, "PERMISSION_DENIED");
    }

    @Test
    void aRefusedCodeIsTheCallersFaultAndABrokenProviderIsNot() throws Exception {
        profile("kakao", KAKAO_OK);
        ANSWERS.put("/kakao/token", new String[]{"400", "{\"error\":\"invalid_grant\",\"error_description\":\"used\"}"});
        ResultActions refused = signIn("kakao", AGREED);
        expectError(refused, 400, "VALIDATION_ERROR");
        assertThat(refused.andReturn().getResponse().getContentAsString()).doesNotContain("invalid_grant");
        // Naver may say no inside a 200.
        ANSWERS.put("/naver/token", new String[]{"200", "{\"error\":\"invalid_request\",\"error_description\":\"no\"}"});
        expectError(signIn("naver", AGREED), 400, "VALIDATION_ERROR");

        // Our own key refused, an outage, an answer that is not JSON, a profile that cannot be read.
        ANSWERS.put("/kakao/token", new String[]{"401", "{\"error\":\"invalid_client\"}"});
        expectError(signIn("kakao", AGREED), 503, "SERVICE_UNAVAILABLE");
        ANSWERS.put("/kakao/token", new String[]{"502", "<html>bad gateway</html>"});
        expectError(signIn("kakao", AGREED), 503, "SERVICE_UNAVAILABLE");
        ANSWERS.put("/kakao/token", new String[]{"200", "{\"access_token\":\"kakao-token\"}"});
        ANSWERS.put("/kakao/me", new String[]{"401", "{\"msg\":\"no\"}"});
        expectError(signIn("kakao", AGREED), 503, "SERVICE_UNAVAILABLE");
        profile("naver", "{'resultcode':'024','message':'Authentication failed'}");
        ANSWERS.put("/naver/token", new String[]{"200", "{\"access_token\":\"naver-token\"}"});
        expectError(signIn("naver", AGREED), 503, "SERVICE_UNAVAILABLE");
        ANSWERS.put("/naver/token", new String[]{"200", "null"});
        expectError(signIn("naver", AGREED), 503, "SERVICE_UNAVAILABLE");
        assertThat(count("select count(*) from users")).isZero();
    }

    @Test
    void theRequestIsStrictAndCounted() throws Exception {
        profile("kakao", KAKAO_OK);
        expectError(post(SOCIAL + "apple", null, q("{'authorization_code':'c'}")), 404, "NOT_FOUND");
        expectError(post(SOCIAL + "kakao", null, null), 400, "VALIDATION_ERROR");
        for (String bad : List.of("{}", "{'authorization_code':''}", "{'authorization_code':5,'redirect_uri':'http://localhost:3000/cb'}",
                // Kakao and Google exchange a code only together with the address it was issued for.
                "{'authorization_code':'c'}", "{'authorization_code':'c','redirect_uri':'http://localhost:3000/cb','extra':1}")) {
            expectError(post(SOCIAL + "kakao", null, q(bad)), 400, "VALIDATION_ERROR");
        }
        // Naver needs the state the sign-in started with.
        expectError(post(SOCIAL + "naver", null, q("{'authorization_code':'c'}")), 400, "VALIDATION_ERROR");
        assertThat(SEEN).isEmpty();

        // The calls above were counted against this address; now its window is full.
        assertThat(jdbc.sql("update auth_rate_limits set hits = 1000 where key_digest = ?")
                .param(digests.of("rate", "social:ip:127.0.0.1")).update()).isEqualTo(1);
        expectError(signIn("kakao", AGREED), 429, "RATE_LIMITED");
        assertThat(SEEN).isEmpty();
    }
}
