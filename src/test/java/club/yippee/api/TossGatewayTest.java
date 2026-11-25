package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import club.yippee.api.order.PaymentGateway.Payment;
import club.yippee.api.order.PaymentGateway.State;
import club.yippee.api.order.TossGateway;

/** The one class that talks to Toss Payments, against a local stand-in server. Nothing leaves this machine. */
class TossGatewayTest {

    private HttpServer server;
    private TossGateway toss;
    private final List<HttpExchange> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    /** What the stand-in answers next: status and JSON, by request path. */
    private int status = 200;
    private String answer = "{}";
    private long delayMillis;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean lookup = exchange.getRequestURI().getPath().startsWith("/v1/payments/orders/");
            int code = lookup && lookupStatus != 0 ? lookupStatus : status;
            byte[] body = (lookup && lookupAnswer != null ? lookupAnswer : answer).getBytes(StandardCharsets.UTF_8);
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        toss = gateway("test_sk_review");
    }

    private int lookupStatus;
    private String lookupAnswer;

    private TossGateway gateway(String secret) {
        return new TossGateway("http://127.0.0.1:" + server.getAddress().getPort(), secret,
                Duration.ofSeconds(5), Duration.ofMillis(400));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * The same class against the real Toss Payments test server. It runs only when TOSS_SECRET_KEY
     * holds a test key, never a live one, so the ordinary test run and CI skip it. It asks about
     * payments that do not exist: no money moves and nothing is created there. What it proves is
     * that the key is accepted, the addresses are right, and the real answers are read as we assume.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TOSS_SECRET_KEY", matches = "test_sk_.+")
    void theRealTestServerAcceptsOurKeyAndAnswersAsWeRead() {
        String address = "https://api.tosspayments.com";
        TossGateway real = new TossGateway(address, System.getenv("TOSS_SECRET_KEY"), Duration.ofSeconds(30), Duration.ofSeconds(10));
        String order = "ord_live_check_" + System.nanoTime();
        Payment looked = real.lookup(order);
        Payment confirmed = real.confirm("no_such_payment_key_" + System.nanoTime(), order, 1900);
        Payment cancelled = real.cancel("no_such_payment_key_" + System.nanoTime(), "연동 확인");
        Payment wrongKey = new TossGateway(address, "test_sk_not_a_real_key", Duration.ofSeconds(30), Duration.ofSeconds(10))
                .lookup(order);
        System.out.println("TOSS_LIVE lookup=" + looked.state() + "/" + looked.code()
                + " confirm=" + confirmed.state() + "/" + confirmed.code()
                + " cancel=" + cancelled.state() + "/" + cancelled.code()
                + " wrong_key=" + wrongKey.state() + "/" + wrongKey.code());
        // A payment that does not exist is refused. A key that is wrong is "not known yet", never
        // "refused": a broken setup must not end a customer's order.
        assertThat(looked.state()).isEqualTo(State.FAILED);
        assertThat(confirmed.state()).isEqualTo(State.FAILED);
        assertThat(cancelled.state()).isEqualTo(State.FAILED);
        assertThat(wrongKey.state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void confirmSendsOurAmountWithBasicAuthAndARepeatableKey() {
        answer = "{\"status\":\"DONE\",\"totalAmount\":2890,\"paymentKey\":\"pk_1\",\"orderId\":\"ord_1\","
                + "\"receipt\":{\"url\":\"https://receipt.example/1\"}}";
        Payment paid = toss.confirm("pk_1", "ord_1", 2890);
        assertThat(paid).isEqualTo(new Payment(State.DONE, 2890, "pk_1", "https://receipt.example/1", "DONE"));

        HttpExchange call = seen.get(0);
        assertThat(call.getRequestMethod()).isEqualTo("POST");
        assertThat(call.getRequestURI().getPath()).isEqualTo("/v1/payments/confirm");
        assertThat(call.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("test_sk_review:".getBytes(StandardCharsets.UTF_8)));
        assertThat(bodies.get(0)).contains("\"paymentKey\":\"pk_1\"").contains("\"orderId\":\"ord_1\"").contains("\"amount\":2890");

        toss.confirm("pk_1", "ord_1", 2890);
        toss.confirm("pk_2", "ord_1", 2890);
        String first = seen.get(0).getRequestHeaders().getFirst("Idempotency-Key");
        assertThat(first).hasSize(64).doesNotContain("pk_1");
        assertThat(seen.get(1).getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(first);
        assertThat(seen.get(2).getRequestHeaders().getFirst("Idempotency-Key")).isNotEqualTo(first);
    }

    @Test
    void statusWordsMapToWhatTheOrderNeedsToKnow() {
        String[][] cases = {{"DONE", "DONE"}, {"CANCELED", "CANCELED"}, {"PARTIAL_CANCELED", "REVIEW"}, {"ABORTED", "FAILED"},
                {"EXPIRED", "FAILED"}, {"IN_PROGRESS", "PENDING"}, {"WAITING_FOR_DEPOSIT", "PENDING"}, {"READY", "PENDING"}};
        for (String[] pair : cases) {
            answer = "{\"status\":\"" + pair[0] + "\",\"totalAmount\":100}";
            assertThat(toss.lookup("ord_1").state()).as(pair[0]).isEqualTo(State.valueOf(pair[1]));
        }
        assertThat(seen.get(0).getRequestMethod()).isEqualTo("GET");
        assertThat(seen.get(0).getRequestURI().getPath()).isEqualTo("/v1/payments/orders/ord_1");
    }

    @Test
    void aRefusalIsFinalButAnOutageOrAKeyProblemIsNot() {
        String[][] cases = {{"400", "REJECT_CARD_COMPANY", "FAILED"}, {"404", "NOT_FOUND_PAYMENT_SESSION", "FAILED"},
                {"400", "PROVIDER_ERROR", "UNKNOWN"}, {"409", "IDEMPOTENT_REQUEST_PROCESSING", "UNKNOWN"},
                {"401", "UNAUTHORIZED_KEY", "UNKNOWN"}, {"500", "UNKNOWN_PAYMENT_ERROR", "UNKNOWN"},
                // Asked too often, or too slowly: nothing was said about the payment itself.
                {"429", "TOO_MANY_REQUESTS", "UNKNOWN"}, {"408", "REQUEST_TIMEOUT", "UNKNOWN"}};
        for (String[] row : cases) {
            status = Integer.parseInt(row[0]);
            answer = "{\"code\":\"" + row[1] + "\",\"message\":\"m\"}";
            Payment result = toss.confirm("pk_1", "ord_" + row[1], 100);
            assertThat(result.state()).as(row[1]).isEqualTo(State.valueOf(row[2]));
            assertThat(result.code()).isEqualTo(row[1]);
        }
        status = 502;
        answer = "<html>bad gateway</html>";
        assertThat(toss.confirm("pk_1", "ord_x", 100).state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void alreadyProcessedIsSettledByLookingThePaymentUp() {
        status = 400;
        answer = "{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"m\"}";
        lookupStatus = 200;
        lookupAnswer = "{\"status\":\"DONE\",\"totalAmount\":500,\"paymentKey\":\"pk_1\"}";
        Payment result = toss.confirm("pk_1", "ord_1", 500);
        assertThat(result.state()).isEqualTo(State.DONE);
        assertThat(result.totalAmount()).isEqualTo(500);
        assertThat(seen).hasSize(2);
    }

    @Test
    void aSlowAnswerIsUnknownNotFailed() {
        delayMillis = 1500;
        answer = "{\"status\":\"DONE\",\"totalAmount\":100}";
        assertThat(toss.lookup("ord_1").state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void cancelAsksForAFullRefundAndRepeatsSafely() {
        answer = "{\"status\":\"CANCELED\",\"totalAmount\":500}";
        toss.cancel("pk_1", "고객 요청");
        toss.cancel("pk_1", "다시 누름");
        assertThat(seen.get(0).getRequestMethod()).isEqualTo("POST");
        assertThat(seen.get(0).getRequestURI().getPath()).isEqualTo("/v1/payments/pk_1/cancel");
        assertThat(bodies.get(0)).contains("\"cancelReason\":\"고객 요청\"").doesNotContain("cancelAmount");
        assertThat(seen.get(1).getRequestHeaders().getFirst("Idempotency-Key"))
                .isEqualTo(seen.get(0).getRequestHeaders().getFirst("Idempotency-Key"));
    }

    @Test
    void withoutAKeyNothingIsSent() {
        assertThat(gateway("").confirm("pk_1", "ord_1", 100)).isEqualTo(Payment.of(State.UNKNOWN, "NOT_CONFIGURED"));
        assertThat(seen).isEmpty();
    }
}
