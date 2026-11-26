package club.yippee.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import club.yippee.api.account.AccountService;
import club.yippee.api.account.Mailer;
import club.yippee.api.catalog.Activities;
import club.yippee.api.common.Digests;
import club.yippee.api.order.PaymentGateway;

/** Real requests through the security filters, against H2 with the Flyway schema. Mail is captured. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestBase {

    protected static final String PASSWORD = "SecureAccountPwd!83927";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected Digests digests;
    @MockitoBean
    protected Mailer mailer;
    /** The real service, wrapped so one test can make it fail. */
    @MockitoSpyBean
    protected AccountService accounts;

    /** The real catalogue, wrapped so one test can act in the middle of an order being made. */
    @MockitoSpyBean
    protected Activities catalogue;
    /** Stands in for the payment company. No test ever calls the real one. */
    @MockitoBean
    protected PaymentGateway gateway;

    @BeforeEach
    void emptyTables() {
        // Money records first: nothing cascades into them. The rest goes with users and activities.
        for (String table : new String[]{"withdrawn_members", "refund_requests", "pdf_downloads", "purchase_entitlements", "order_items",
                "orders", "users", "activities",
                "categories", "email_verifications", "auth_rate_limits"}) {
            jdbc.sql("delete from " + table).update();
        }
        when(gateway.lookup(anyString())).thenReturn(PaymentGateway.Payment.of(PaymentGateway.State.UNKNOWN, "TEST"));
        when(gateway.ready()).thenReturn(true);
    }

    protected ResultActions call(String method, String path, String token, String json) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }

    protected ResultActions get(String path, String token) throws Exception {
        return call("GET", path, token, null);
    }

    protected ResultActions post(String path, String token, String json) throws Exception {
        return call("POST", path, token, json);
    }

    protected ResultActions patch(String path, String token, String json) throws Exception {
        return call("PATCH", path, token, json);
    }

    protected ResultActions put(String path, String token, String json) throws Exception {
        return call("PUT", path, token, json);
    }

    protected ResultActions delete(String path, String token) throws Exception {
        return call("DELETE", path, token, null);
    }

    /** JSON written with single quotes, for readability in Java source. */
    protected static String q(String json) {
        return json.replace('\'', '"');
    }

    protected static <T> T read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    protected long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** The plain-text body of the last mail the server tried to send. */
    protected String lastMailText() throws Exception {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).send(anyString(), anyString(), text.capture(), any());
        return text.getValue();
    }

    /** Stores a verified, unused email proof, as if the code had just been confirmed. Returns its token. */
    protected String proof(String email) {
        String token = Digests.randomToken();
        LocalDateTime now = utc(Instant.now());
        jdbc.sql("insert into email_verifications (id, email, purpose, code_digest, attempts, expires_at,"
                        + " verified_at, token_digest, token_expires_at, created_at)"
                        + " values (?, ?, 'signup', '', 0, ?, ?, ?, ?, ?)")
                .params("ver_" + Digests.randomHex(8), email, now.plusMinutes(3), now,
                        digests.of("verification-token", token), now.plusMinutes(10), now)
                .update();
        return token;
    }

    protected record Session(long userId, String access, String refresh) {
    }

    /** A new account, through the real signup endpoint. */
    protected Session signUp(String email) throws Exception {
        ResultActions created = post("/api/v1/auth/signup", null, signupBody(email, proof(email), PASSWORD))
                .andExpect(status().isCreated());
        Number userId = read(created, "$.user_id");
        return new Session(userId.longValue(), read(created, "$.access_token"), read(created, "$.refresh_token"));
    }

    protected static String signupBody(String email, String proof, String password) {
        return q("{'identifier':'%s','verification_token':'%s','password':'%s','agreements':{'terms':true}}"
                .formatted(email, proof, password));
    }

    /** The server stores times as UTC wall-clock values. */
    protected static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Fills a rate-limit window, as if that many requests had just arrived. */
    protected void seedLimit(String scope, String identity, int hits) {
        jdbc.sql("insert into auth_rate_limits (key_digest, window_start, hits) values (?, ?, ?)")
                .params(digests.of("rate", scope + ":" + identity), Instant.now().getEpochSecond(), hits)
                .update();
    }

    /** The response is an error of this status in the spec's four-key shape. */
    protected static ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details").isMap())
                .andExpect(jsonPath("$.request_id").isString())
                .andExpect(jsonPath("$.length()").value(4));
    }
}
