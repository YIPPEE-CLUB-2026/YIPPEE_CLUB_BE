package club.yippee.api.order;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.catalog.CatalogController;
import club.yippee.api.common.ApiException;
import club.yippee.api.common.Fields;

/**
 * What the people running the service need for payments: see the orders that wait for a person,
 * ask the payment company about one again, and refund one. Admin accounts only.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class OrderAdminController {

    private static final Set<String> STATUSES =
            Set.of("pending", "paid", "payment_failed", "cancelled", "refunded", "review");

    private final OrderService orders;

    OrderAdminController(OrderService orders) {
        this.orders = orders;
    }

    /** ?status=review lists what needs a person. Without it, every order, newest first. */
    @GetMapping
    Map<String, Object> list(@RequestParam Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>(params.keySet());
        faults.removeAll(Set.of("status", "page", "page_size"));
        String status = params.get("status");
        if (status != null && !STATUSES.contains(status)) {
            faults.add("status");
        }
        Integer page = CatalogController.whole(params, "page", 1, 1_000_000, faults);
        Integer size = CatalogController.whole(params, "page_size", 1, 100, faults);
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        return orders.adminList(status, page == null ? 1 : page, size == null ? 20 : size);
    }

    /** One order with the record a dispute needs: notice agreed, paid, and each PDF delivered, with times. */
    @GetMapping("/{id:[0-9]{1,18}}")
    Map<String, Object> detail(@PathVariable Long id) {
        return orders.adminDetail(id);
    }

    @PostMapping("/{id:[0-9]{1,18}}/reconcile")
    Map<String, Object> reconcile(@PathVariable Long id) {
        return orders.adminReconcile(id);
    }

    @PostMapping("/{id:[0-9]{1,18}}/refund")
    Map<String, Object> refund(@PathVariable Long id, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "reason");
        String reason = fields.text("reason", 200, false);
        fields.check();
        return orders.refund(id, reason);
    }

    /** ?status=requested lists what waits for a decision. Each row carries the reason and the download record. */
    @GetMapping("/refund-requests")
    Map<String, Object> refundRequests(@RequestParam Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>(params.keySet());
        faults.removeAll(Set.of("status", "page", "page_size"));
        String status = params.get("status");
        if (status != null && !Set.of("requested", "approved", "rejected").contains(status)) {
            faults.add("status");
        }
        Integer page = CatalogController.whole(params, "page", 1, 1_000_000, faults);
        Integer size = CatalogController.whole(params, "page_size", 1, 100, faults);
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        return orders.refundRequests(status, page == null ? 1 : page, size == null ? 20 : size);
    }

    @PostMapping("/refund-requests/{id:[0-9]{1,18}}/approve")
    Map<String, Object> approve(@PathVariable Long id) {
        return orders.approveRefund(id);
    }

    /** The note is what the buyer reads as the answer. */
    @PostMapping("/refund-requests/{id:[0-9]{1,18}}/reject")
    Map<String, Object> reject(@PathVariable Long id, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "note");
        String note = fields.text("note", 500, false);
        fields.check();
        return orders.rejectRefund(id, note);
    }
}
