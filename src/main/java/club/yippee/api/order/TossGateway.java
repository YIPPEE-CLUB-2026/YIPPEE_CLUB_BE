package club.yippee.api.order;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import club.yippee.api.common.AppProperties;

/**
 * Toss Payments, core API v1. This is the only class that knows the company's addresses,
 * headers and status words. The secret key and the request headers are never logged.
 */
@Component
public class TossGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(TossGateway.class);
    // Answers that say "not now" or "your setup is wrong": the customer's payment is not refused by them.
    private static final Set<String> NOT_FINAL = Set.of("PROVIDER_ERROR", "ALREADY_PROCESSING_REQUEST",
            "IDEMPOTENT_REQUEST_PROCESSING", "ALREADY_PROCESSED_PAYMENT", "INVALID_API_KEY", "UNAUTHORIZED_KEY",
            "INCORRECT_BASIC_AUTH_FORMAT");

    private final String baseUrl;
    private final String authorization;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration confirmTimeout;
    private final Duration lookupTimeout;

    @Autowired
    public TossGateway(AppProperties properties) {
        // The webhook must be answered within 10 seconds, and its lookup runs inside that.
        this(properties.tossBaseUrl(), properties.tossSecretKey(), Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    public TossGateway(String baseUrl, String secretKey, Duration confirmTimeout, Duration lookupTimeout) {
        this.baseUrl = baseUrl;
        this.authorization = secretKey == null || secretKey.isEmpty() ? null
                : "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.confirmTimeout = confirmTimeout;
        this.lookupTimeout = lookupTimeout;
    }

    @Override
    public boolean ready() {
        return authorization != null;
    }

    @Override
    public Payment confirm(String paymentKey, String orderId, long amount) {
        String body = json.writeValueAsString(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount));
        Payment answer = send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments/confirm"))
                .timeout(confirmTimeout)
                .header("Content-Type", "application/json")
                // The same key for the same payment of the same order: a resend after a timeout gets
                // the first answer back and cannot charge twice. A new payment key is a new attempt.
                .header("Idempotency-Key", sha256("confirm:" + orderId + ":" + paymentKey))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        return "ALREADY_PROCESSED_PAYMENT".equals(answer.code()) ? lookup(orderId) : answer;
    }

    @Override
    public Payment cancel(String paymentKey, String reason) {
        String body = json.writeValueAsString(Map.of("cancelReason", reason));
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/payments/"
                        + URLEncoder.encode(paymentKey, StandardCharsets.UTF_8) + "/cancel"))
                .timeout(confirmTimeout)
                .header("Content-Type", "application/json")
                // One key per payment: pressing refund twice returns the money once.
                .header("Idempotency-Key", sha256("cancel:" + paymentKey))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    @Override
    public Payment lookup(String orderId) {
        return send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/payments/orders/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8)))
                .timeout(lookupTimeout).GET());
    }

    private Payment send(HttpRequest.Builder request) {
        if (authorization == null) {
            log.error("toss_secret_key_missing");
            return Payment.of(State.UNKNOWN, "NOT_CONFIGURED");
        }
        try {
            HttpResponse<String> response = http.send(request.header("Authorization", authorization).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<?, ?> body = json.readValue(response.body(), Map.class);
            int status = response.statusCode();
            if (status == 200) {
                return payment(body);
            }
            String code = String.valueOf(body.get("code"));
            log.warn("toss_error status={} code={}", status, code);
            // 408 and 429 say nothing about the payment: we asked too slowly or too often. Read as a
            // refusal, a throttled lookup would close an order whose payment is about to go through.
            boolean undecided = status >= 500 || status == 401 || status == 403 || status == 408 || status == 409
                    || status == 429 || NOT_FINAL.contains(code);
            return Payment.of(undecided ? State.UNKNOWN : State.FAILED, code);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Payment.of(State.UNKNOWN, "INTERRUPTED");
        } catch (IOException | RuntimeException noUsableAnswer) {
            // Timeout, refused connection, or a body that is not the JSON we expect.
            log.warn("toss_no_answer {}", noUsableAnswer.getClass().getName());
            return Payment.of(State.UNKNOWN, "NO_ANSWER");
        }
    }

    private static Payment payment(Map<?, ?> body) {
        String status = String.valueOf(body.get("status"));
        State state = switch (status) {
            case "DONE" -> State.DONE;
            case "CANCELED" -> State.CANCELED;
            case "PARTIAL_CANCELED" -> State.REVIEW;
            case "ABORTED", "EXPIRED" -> State.FAILED;
            default -> State.PENDING;
        };
        long total = body.get("totalAmount") instanceof Number number ? number.longValue() : -1;
        String receipt = body.get("receipt") instanceof Map<?, ?> map && map.get("url") instanceof String url ? url : null;
        return new Payment(state, total, body.get("paymentKey") instanceof String key ? key : null, receipt, status);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
