package club.yippee.api.order;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.catalog.CatalogController;
import club.yippee.api.common.ApiException;
import club.yippee.api.common.Fields;
import club.yippee.api.common.RateLimiter;

/** Orders, payment, purchases and downloads. The buyer is always the user in the token. */
@RestController
public class OrderController {

    private static final int MAX_ITEMS = 50;
    /** Far more than a buyer needs, and an end to one account feeding a sheet's link to many. */
    private static final int LINKS_PER_SHEET_PER_HOUR = 10;
    /** A buyer confirms once per payment attempt; this leaves room for many failed cards. */
    private static final int CONFIRMS_PER_TEN_MINUTES = 20;
    private static final int ORDERS_PER_TEN_MINUTES = 30;

    private final OrderService orders;
    private final RateLimiter limiter;

    OrderController(OrderService orders, RateLimiter limiter) {
        this.orders = orders;
        this.limiter = limiter;
    }

    @PostMapping("/api/v1/orders/quote")
    Map<String, Object> quote(@AuthenticationPrincipal Long userId, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "activity_ids", "rental_activity_ids");
        List<Long> ids = ids(fields, "activity_ids");
        List<Long> rentals = rentalIds(fields, ids);
        fields.check();
        return orders.quote(userId, ids, rentals);
    }

    @PostMapping("/api/v1/orders")
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Long userId,
                                               @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "activity_ids", "rental_activity_ids", "payment_method_id",
                "agreed_to_payment", "agreed_to_refund_notice");
        List<Long> ids = ids(fields, "activity_ids");
        List<Long> rentals = rentalIds(fields, ids);
        if (!Boolean.TRUE.equals(fields.raw("agreed_to_payment"))) {
            fields.reject("agreed_to_payment");
        }
        // Must be true for an order that costs money (the service knows the total). Never anything but a boolean.
        Object notice = fields.raw("agreed_to_refund_notice");
        if (notice != null && !(notice instanceof Boolean)) {
            fields.reject("agreed_to_refund_notice");
        }
        if (fields.has("payment_method_id") && !fields.isNull("payment_method_id")) {
            // Stored cards do not exist yet; accepting an id here would pretend they do.
            fields.reject("payment_method_id");
        }
        fields.check();
        // Each order gets a number of its own that the open webhook will take questions about.
        limiter.consume("order", String.valueOf(userId), ORDERS_PER_TEN_MINUTES, 600);
        return ResponseEntity.status(201).body(orders.create(userId, ids, rentals, Boolean.TRUE.equals(notice)));
    }

    @GetMapping("/api/v1/orders")
    Map<String, Object> list(@AuthenticationPrincipal Long userId, @RequestParam Map<String, String> params) {
        int[] page = page(params);
        return orders.list(userId, page[0], page[1]);
    }

    @GetMapping("/api/v1/orders/{id:[0-9]+}")
    Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable String id) {
        return orders.detail(userId, number(id));
    }

    @GetMapping("/api/v1/orders/{id:[0-9]+}/receipt")
    Map<String, Object> receipt(@AuthenticationPrincipal Long userId, @PathVariable String id) {
        return orders.receipt(userId, number(id));
    }

    @PostMapping("/api/v1/orders/{id:[0-9]+}/cancel")
    ResponseEntity<Map<String, Object>> cancel(@AuthenticationPrincipal Long userId, @PathVariable String id,
                                               @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "reason");
        String reason = fields.text("reason", 500, false);
        fields.check();
        return ResponseEntity.status(201).body(orders.cancel(userId, number(id), reason));
    }

    @PostMapping("/api/v1/payments/confirm")
    Map<String, Object> confirm(@AuthenticationPrincipal Long userId, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "order_id", "pg_payment_key", "pg_order_id");
        Long orderId = fields.id("order_id");
        String paymentKey = fields.text("pg_payment_key", 200, false);
        String pgOrderId = fields.text("pg_order_id", 64, false);
        fields.check();
        // Each call can hold a server thread on the payment company for up to 30 seconds.
        limiter.consume("confirm", String.valueOf(userId), CONFIRMS_PER_TEN_MINUTES, 600);
        try {
            return orders.confirm(userId, orderId, paymentKey, pgOrderId);
        } catch (DataIntegrityViolationException keyUsedByAnotherOrder) {
            throw new ApiException(409, "INVALID_STATE", "이미 사용된 결제입니다.");
        }
    }

    /**
     * Called by the payment company, with no token. The body is only a hint: the one thing read
     * from it is which order to ask the company about, with our own key.
     */
    @PostMapping("/api/v1/payments/webhook")
    Map<String, Object> webhook(@RequestBody(required = false) Object body) {
        Fields fields = Fields.lenient(body);
        Object orderId = fields.raw("data") instanceof Map<?, ?> data ? data.get("orderId") : null;
        if (!(orderId instanceof String pgOrderId) || pgOrderId.length() > 64) {
            throw ApiException.field("data");
        }
        if (!orders.notified(pgOrderId)) {
            // Anything but 200 makes the company send the event again later.
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "결제 확인이 지연되고 있습니다.");
        }
        return Map.of("received", true);
    }

    @GetMapping("/api/v1/purchases/activities")
    Map<String, Object> purchases(@AuthenticationPrincipal Long userId, @RequestParam Map<String, String> params) {
        int[] page = page(params);
        return orders.purchases(userId, page[0], page[1]);
    }

    @PostMapping("/api/v1/activities/{id:[0-9]+}/download")
    Map<String, Object> download(@AuthenticationPrincipal Long userId, @PathVariable String id) {
        Long activityId = number(id);
        // Counted here, before the service's transaction: the limiter works on a connection of its own.
        limiter.consume("download", userId + ":" + activityId, LINKS_PER_SHEET_PER_HOUR, 3600);
        return orders.download(userId, activityId);
    }

    private static Long number(String digits) {
        if (digits.length() > 18) {
            throw ApiException.notFound();
        }
        return Long.valueOf(digits);
    }

    // page and page_size only; defaults 1 and 20.
    private static int[] page(Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>(params.keySet());
        faults.removeAll(Set.of("page", "page_size"));
        Integer page = CatalogController.whole(params, "page", 1, 1_000_000, faults);
        Integer size = CatalogController.whole(params, "page_size", 1, 100, faults);
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        return new int[]{page == null ? 1 : page, size == null ? 20 : size};
    }

    // 1 to 50 distinct activity ids, each a real JSON integer.
    private static List<Long> ids(Fields fields, String name) {
        List<Long> ids = new ArrayList<>();
        if (fields.raw(name) instanceof List<?> items && !items.isEmpty() && items.size() <= MAX_ITEMS) {
            for (Object item : items) {
                Long id = Fields.asId(item);
                if (id == null) {
                    fields.reject(name);
                    return ids;
                }
                ids.add(id);
            }
            if (new HashSet<>(ids).size() == ids.size()) {
                return ids;
            }
        }
        fields.reject(name);
        return ids;
    }

    // The part of the order bought for 7 days instead of for good. Left out or empty: none.
    // Every id in it must be one of the ordered ids.
    private static List<Long> rentalIds(Fields fields, List<Long> ordered) {
        if (!fields.has("rental_activity_ids")
                || (fields.raw("rental_activity_ids") instanceof List<?> items && items.isEmpty())) {
            return List.of();
        }
        List<Long> rentals = ids(fields, "rental_activity_ids");
        if (!ordered.containsAll(rentals)) {
            fields.reject("rental_activity_ids");
        }
        return rentals;
    }
}
