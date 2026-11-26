package club.yippee.api.order;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import club.yippee.api.catalog.Activities;
import club.yippee.api.catalog.Activity;
import club.yippee.api.catalog.Issuances;
import club.yippee.api.common.ApiException;
import club.yippee.api.common.Digests;
import club.yippee.api.common.RateLimiter;
import club.yippee.api.order.PaymentGateway.Payment;

/**
 * Orders, payment and the right to download what was bought.
 *
 * The rules that protect money:
 * - The amount is summed from stored prices when the order is made. A request never supplies it.
 * - A user has at most one order on its way to payment, so one activity cannot be paid for twice.
 * - No transaction or row lock is open while the payment company is being called.
 * - The right to download is granted only after the company says DONE for exactly our amount.
 * - Settling is the same code for the confirm call, the webhook and the catch-up job, and it is
 *   safe to run twice or on several servers at once: it works under the order's row lock.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final Activities activities;
    private final Issuances issuances;
    private final PaymentGateway gateway;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final RateLimiter limiter;

    OrderService(OrderRepository orders, Activities activities, Issuances issuances, PaymentGateway gateway, JdbcClient jdbc,
                 PlatformTransactionManager transactions, RateLimiter limiter) {
        this.limiter = limiter;
        this.orders = orders;
        this.activities = activities;
        this.issuances = issuances;
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The two ways to buy a sheet: for good, or for 7 days at its lower price. */
    static final String PERMANENT = "permanent";
    static final String RENTAL = "rental";

    // What the user holds of this activity right now: PERMANENT, RENTAL (its days still running) or null.
    // A pass whose days are over counts as nothing held, though its row stays for the buyer's list.
    private String held(Long userId, Long activityId) {
        LocalDateTime now = utc(Instant.now());
        return jdbc.sql("select expires_at from purchase_entitlements"
                        + " where user_id = ? and activity_id = ? and status = 'active'")
                .params(userId, activityId)
                .query((row, index) -> {
                    LocalDateTime expires = row.getObject("expires_at", LocalDateTime.class);
                    return expires == null ? PERMANENT : expires.isAfter(now) ? RENTAL : "";
                }).optional().filter(plan -> !plan.isEmpty()).orElse(null);
    }

    // What is owned for good cannot be bought again. What is on a running pass can only be bought for good.
    private boolean alreadyHas(Long userId, Long activityId, String plan) {
        String held = held(userId, activityId);
        return PERMANENT.equals(held) || (RENTAL.equals(held) && plan.equals(RENTAL));
    }

    // Today's price of this way of buying; null when the sheet has no 7-day pass.
    private static Integer price(Activity activity, String plan) {
        return plan.equals(RENTAL) ? activity.rentalPrice() : Integer.valueOf(activity.price());
    }

    /**
     * What these activities would cost now, and which of them cannot be bought (hidden, missing,
     * already held, or asked for 7 days when no 7-day pass is sold). rentalIds are bought for 7 days.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> quote(Long userId, List<Long> activityIds, List<Long> rentalIds) {
        long subtotal = 0;
        List<Long> unavailable = new ArrayList<>();
        for (Long id : activityIds) {
            String plan = rentalIds.contains(id) ? RENTAL : PERMANENT;
            Integer price = activities.findPublic(id).map(activity -> price(activity, plan)).orElse(null);
            if (price == null || alreadyHas(userId, id, plan)) {
                unavailable.add(id);
            } else {
                subtotal += price;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subtotal", subtotal);
        body.put("payable_amount", subtotal);
        body.put("unavailable_activity_ids", unavailable);
        // The exact words the buyer must tick before paying. Shown from here so the page and the record agree.
        body.put("refund_notice", REFUND_NOTICE);
        return body;
    }

    /**
     * The notice a buyer agrees to before paying. Once the file is downloaded the law lets a seller
     * refuse a change-of-mind refund, but only if the buyer was told so beforehand. What the law keeps
     * for the buyer is said too, because a notice that takes those rights away would not hold:
     * withdrawal within 7 days while nothing was downloaded, and a file that is not as described.
     */
    static final String REFUND_NOTICE = "결제 후 파일을 내려받거나 열람하면 전자상거래법 제17조 제2항에 따라"
            + " 청약철회(환불)가 제한됩니다. 내려받기 전에는 결제일부터 7일 이내에 환불을 요청할 수 있고,"
            + " 파일이 표시·광고 내용과 다르거나 열리지 않으면 내려받은 뒤에도 환불을 요청할 수 있습니다.";

    /** Makes an order. A total of zero needs no payment and is complete at once. */
    @Transactional
    public Map<String, Object> create(Long userId, List<Long> activityIds, List<Long> rentalIds,
                                      boolean agreedToRefundNotice) {
        lockLiveUser(userId);
        Order order = new Order(userId, "ord_" + Digests.randomHex(16));
        for (Long id : activityIds) {
            Activity activity = activities.findPublic(id).orElseThrow(() -> ApiException.field("activity_ids"));
            String plan = rentalIds.contains(id) ? RENTAL : PERMANENT;
            Integer price = price(activity, plan);
            if (price == null) {
                throw ApiException.field("rental_activity_ids");
            }
            if (alreadyHas(userId, id, plan)) {
                throw new ApiException(409, "ALREADY_PURCHASED", "이미 구매한 활동지입니다.");
            }
            order.items.add(new Order.Item(activity.id(), activity.title(), price, plan));
            order.amount = Math.addExact(order.amount, price);
        }
        // For one user and one activity, at most one order is ever on its way to payment.
        for (Order earlier : orders.lockPendingOf(userId)) {
            if (earlier.confirmStartedAt == null) {
                // Nobody started paying for it; the new order takes its place.
                earlier.status = Order.CANCELLED;
            } else if (earlier.items.stream().anyMatch(item -> activityIds.contains(item.activityId()))) {
                // Its payment may still succeed. A second order for the same activity could charge twice.
                // An unsettled order for other activities does not stand in the way.
                throw new ApiException(409, "INVALID_STATE", "결제가 진행 중인 주문이 있습니다.");
            }
        }
        if (order.amount == 0) {
            order.status = Order.PAID;
            order.paidAt = Instant.now();
        } else {
            // No order that costs money exists without the buyer's agreement to the refund notice.
            if (!agreedToRefundNotice) {
                throw ApiException.field("agreed_to_refund_notice");
            }
            order.refundNoticeAgreedAt = Instant.now();
            requireGateway();
        }
        orders.saveAndFlush(order);
        if (order.amount == 0) {
            grant(order);
        }
        return summary(order);
    }

    /**
     * Takes the payment the customer authenticated in the payment window. Three steps: mark the
     * order (short transaction), ask the company (no transaction), record the answer (short transaction).
     */
    public Map<String, Object> confirm(Long userId, Long orderId, String paymentKey, String pgOrderId) {
        requireGateway();
        Order order = transaction.execute(status -> begin(userId, orderId, paymentKey, pgOrderId));
        if (order.status.equals(Order.PENDING)) {
            Payment payment = gateway.confirm(paymentKey, order.pgOrderId, order.amount);
            order = transaction.execute(status -> settle(orderId, payment));
        }
        if (order.status.equals(Order.PAID)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("order_id", order.id);
            body.put("status", order.status);
            body.put("paid_amount", order.amount);
            return body;
        }
        if (order.status.equals(Order.PENDING)) {
            // Not refused, not confirmed. The webhook or the catch-up job will settle it.
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "결제 확인이 지연되고 있습니다. 잠시 후 주문 상태를 확인해 주세요.");
        }
        if (order.status.equals(Order.REVIEW)) {
            throw new ApiException(409, "INVALID_STATE", "결제 금액 확인이 필요합니다. 고객센터로 문의해 주세요.");
        }
        throw new ApiException(409, "INVALID_STATE", "결제가 승인되지 않았습니다.");
    }

    // An order that can never be paid must not be started: once marked in progress, only the
    // payment company's answer can release it, and without a key there is no answer.
    private void requireGateway() {
        if (!gateway.ready()) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "지금은 결제를 사용할 수 없습니다.");
        }
    }

    private Order begin(Long userId, Long orderId, String paymentKey, String pgOrderId) {
        lockLiveUser(userId);
        Order order = orders.lockById(orderId).filter(found -> found.userId.equals(userId))
                .orElseThrow(ApiException::notFound);
        if (order.status.equals(Order.PAID)) {
            return order;
        }
        if (!order.status.equals(Order.PENDING)) {
            throw new ApiException(409, "INVALID_STATE", "결제할 수 없는 주문입니다.");
        }
        if (!order.pgOrderId.equals(pgOrderId)) {
            throw ApiException.field("pg_order_id");
        }
        if (order.pgPaymentKey != null && !order.pgPaymentKey.equals(paymentKey)) {
            throw new ApiException(409, "INVALID_STATE", "다른 결제가 진행 중입니다.");
        }
        order.pgPaymentKey = paymentKey;
        if (order.confirmStartedAt == null) {
            order.confirmStartedAt = Instant.now();
        }
        orders.saveAndFlush(order);
        return order;
    }

    // Records what the payment company said. Runs under the order's row lock; a repeat changes nothing.
    private Order settle(Long orderId, Payment payment) {
        // Same lock order as create and begin: the buyer's row, then the order. Without the buyer's
        // lock, a settle could land between create's ownership check and its look at pending orders,
        // and the buyer could then pay for the same activity a second time.
        lockUser(jdbc.sql("select user_id from orders where id = ?").param(orderId).query(Long.class).single());
        Order order = orders.lockById(orderId).orElseThrow();
        switch (payment.state()) {
            case DONE -> {
                if (!order.status.equals(Order.PENDING)) {
                    if (!order.status.equals(Order.PAID)) {
                        // Money was taken for an order we had already closed. Nothing is granted; a person must refund it.
                        log.error("payment_done_for_closed_order order={} status={}", order.id, order.status);
                    }
                    break;
                }
                if (payment.totalAmount() != order.amount) {
                    // Money moved, but not the amount we asked for. Nothing is granted. The order leaves the
                    // payment flow so the buyer is not blocked, and waits for a person (refund it at the
                    // payment company; the refund's webhook then closes it).
                    log.error("payment_amount_mismatch order={} expected={} paid={}", order.id, order.amount, payment.totalAmount());
                    order.status = Order.REVIEW;
                    break;
                }
                order.status = Order.PAID;
                order.paidAt = Instant.now();
                if (order.pgPaymentKey == null) {
                    order.pgPaymentKey = payment.paymentKey();
                }
                String receipt = payment.receiptUrl();
                order.receiptUrl = receipt != null && receipt.length() <= 500 ? receipt : null;
                grant(order);
            }
            case FAILED -> {
                if (order.status.equals(Order.PENDING)) {
                    order.status = Order.FAILED;
                }
            }
            case CANCELED -> {
                if (order.status.equals(Order.PENDING)) {
                    order.status = Order.CANCELLED;
                } else if (order.status.equals(Order.REVIEW)) {
                    order.status = Order.REFUNDED;
                    order.refundedAt = Instant.now();
                } else if (order.status.equals(Order.PAID)) {
                    // Refunded on the payment company's side (for example from its dashboard).
                    order.status = Order.REFUNDED;
                    order.refundedAt = Instant.now();
                    jdbc.sql("update purchase_entitlements set status = 'revoked', revoked_at = ?"
                            + " where order_id = ? and status = 'active'").params(utc(Instant.now()), order.id).update();
                    // The one place a refund request becomes approved: wherever the refund came from
                    // (an admin's approval, a late webhook, the payment company's own screen).
                    jdbc.sql("update refund_requests set status = 'approved', decided_at = ?"
                            + " where order_id = ? and status = 'requested'").params(utc(Instant.now()), order.id).update();
                }
            }
            case REVIEW -> log.error("payment_needs_review order={} code={}", order.id, payment.code());
            default -> {
                // PENDING or UNKNOWN: nothing is known yet, so nothing changes.
            }
        }
        return order;
    }

    // Gives the buyer the right to each item and takes the items out of the cart. A 7-day pass runs
    // from this moment, the settling of the payment, not from when the order was made.
    private void grant(Order order) {
        LocalDateTime now = utc(Instant.now());
        for (Order.Item item : order.items) {
            LocalDateTime expires = item.plan().equals(RENTAL) ? now.plusDays(Activities.RENTAL_DAYS) : null;
            // There is one row per buyer and sheet, ever. An earlier row is taken over when it was
            // refunded or was a pass (run out, or running and now bought for good). A row owned for
            // good is never taken over: that would be a second payment, and falls to the alarm below.
            // shortcut: the row remembers one order only, so refunding a for-good purchase made
            // during a running pass also ends the days left on that pass. Keep a history of grants
            // if that ever matters.
            int revived = jdbc.sql("update purchase_entitlements set status = 'active', order_id = ?, granted_at = ?,"
                            + " revoked_at = null, expires_at = ? where user_id = ? and activity_id = ?"
                            + " and (status = 'revoked' or expires_at is not null)")
                    .params(order.id, now, expires, order.userId, item.activityId()).update();
            if (revived == 0) {
                try {
                    jdbc.sql("insert into purchase_entitlements (user_id, activity_id, order_id, status, granted_at,"
                                    + " expires_at) values (?, ?, ?, 'active', ?, ?)")
                            .params(order.userId, item.activityId(), order.id, now, expires).update();
                } catch (DuplicateKeyException paidForTwice) {
                    // The one-order-at-a-time rule should make this impossible. If it happens, money was taken twice.
                    log.error("duplicate_entitlement order={} activity={}", order.id, item.activityId());
                }
            }
            jdbc.sql("delete from cart_items where user_id = ? and activity_id = ?")
                    .params(order.userId, item.activityId()).update();
        }
    }

    /**
     * Asks the payment company about one of our orders and records the answer. Used by the webhook
     * and the catch-up job. False means the company could not be reached and the caller should retry.
     */
    public boolean reconcile(String pgOrderId) {
        return reconcile(pgOrderId, false);
    }

    /** How often a minute the open webhook may make us ask the payment company about one order. */
    private static final int WEBHOOK_LOOKUPS_PER_MINUTE = 6;

    /**
     * The webhook's entry. Anyone who knows an order's number can call it, so the questions it sends
     * to the payment company are capped per order. Past the cap it answers false (503), which makes
     * the company send a real event again later; nothing is lost and nothing is asked.
     */
    public boolean notified(String pgOrderId) {
        return reconcile(pgOrderId, true);
    }

    private boolean reconcile(String pgOrderId, boolean capped) {
        Order order = orders.findByPgOrderId(pgOrderId).orElse(null);
        if (order == null) {
            return true;
        }
        boolean open = List.of(Order.PENDING, Order.PAID, Order.REVIEW).contains(order.status);
        if (capped) {
            if (!open && order.confirmStartedAt == null) {
                // Closed before anyone tried to pay it: there is no payment to ask about, and old
                // order numbers must not be a way to make us ask.
                return true;
            }
            try {
                // Counted only for an order of ours: a made-up number leaves no counter behind.
                limiter.consume("webhook", pgOrderId, WEBHOOK_LOOKUPS_PER_MINUTE, 60);
            } catch (ApiException full) {
                // If this goes on, a real event (a refund made on the payment company's own screen)
                // may wait: an admin's reconcile is not capped.
                log.warn("webhook_capped order={}", order.id);
                return false;
            }
        }
        Payment payment = gateway.lookup(pgOrderId);
        if (payment == null || payment.state() == PaymentGateway.State.UNKNOWN) {
            return !open;
        }
        if (!open) {
            if (payment.state() == PaymentGateway.State.DONE) {
                // Money was taken for an order we had already closed. Nothing is granted; a person must refund it.
                log.error("payment_done_for_closed_order order={}", order.id);
            }
            return true;
        }
        if (order.status.equals(Order.PENDING) && order.confirmStartedAt == null
                && payment.state() == PaymentGateway.State.FAILED) {
            // Nobody has asked us to confirm this order yet, so "no such payment" is normal: the
            // buyer may be in the payment window this very moment. The order is left alone.
            return true;
        }
        transaction.execute(status -> settle(order.id, payment));
        return true;
    }

    /** Every minute: orders whose confirm call got no answer are settled by asking again. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void settleUnknown() {
        Instant now = Instant.now();
        for (Long id : orders.unsettledBefore(now.minusSeconds(60), PageRequest.of(0, 50))) {
            try {
                orders.findById(id).ifPresent(this::catchUp);
            } catch (RuntimeException error) {
                log.error("reconcile_failed order={}", id, error);
            }
        }
        transaction.executeWithoutResult(status -> orders.cancelAbandoned(now.minus(1, ChronoUnit.DAYS)));
    }

    private void catchUp(Order order) {
        Payment payment = gateway.lookup(order.pgOrderId);
        if (payment != null && payment.state() == PaymentGateway.State.PENDING
                && "IN_PROGRESS".equals(payment.code()) && order.pgPaymentKey != null) {
            // The customer authenticated, but our confirm call never arrived. Without it the payment
            // expires ten minutes after authentication. Sending it again with the same key is safe.
            payment = gateway.confirm(order.pgPaymentKey, order.pgOrderId, order.amount);
        }
        if (payment != null && payment.state() != PaymentGateway.State.UNKNOWN) {
            Payment answer = payment;
            transaction.execute(status -> settle(order.id, answer));
        }
    }

    // --- for the people running the service (admin only) ---

    /** Orders newest first, optionally of one status. "review" is the list a person must work through. */
    @Transactional(readOnly = true)
    public Map<String, Object> adminList(String status, int page, int pageSize) {
        PageRequest request = PageRequest.of(page - 1, pageSize);
        Page<Order> found = status == null ? orders.findAllByOrderByIdDesc(request)
                : orders.findByStatusOrderByIdDesc(status, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", found.getTotalElements());
        body.put("results", found.getContent().stream().map(OrderService::adminView).toList());
        return body;
    }

    /** Asks the payment company about one order again, for when a webhook was missed. */
    public Map<String, Object> adminReconcile(Long orderId) {
        Order order = orders.findById(orderId).orElseThrow(ApiException::notFound);
        if (!reconcile(order.pgOrderId)) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "결제사에서 답을 받지 못했습니다.");
        }
        return adminView(orders.findById(orderId).orElseThrow());
    }

    /**
     * A full refund decided by a person. The money is returned at the payment company first; the
     * order and the buyer's right change only once the company itself reports the refund.
     */
    public Map<String, Object> refund(Long orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow(ApiException::notFound);
        if (!(order.status.equals(Order.PAID) || order.status.equals(Order.REVIEW)) || order.pgPaymentKey == null) {
            throw new ApiException(409, "INVALID_STATE", "환불할 수 없는 주문입니다.");
        }
        requireGateway();
        Payment answer = gateway.cancel(order.pgPaymentKey, reason);
        // Whatever the answer, what counts is what the company now says about the payment. A refusal
        // can simply mean it was already refunded on the company's own screen.
        reconcile(order.pgOrderId);
        Order after = orders.findById(orderId).orElseThrow();
        if (after.status.equals(Order.REFUNDED)) {
            return adminView(after);
        }
        if (answer != null && answer.state() == PaymentGateway.State.FAILED) {
            throw new ApiException(409, "INVALID_STATE", "결제사가 환불을 받아들이지 않았습니다.");
        }
        throw new ApiException(503, "SERVICE_UNAVAILABLE", "환불 확인이 지연되고 있습니다. 잠시 후 다시 조회해 주세요.");
    }

    /**
     * One order with what a dispute needs (a refund request, or a card company's chargeback):
     * when the buyer agreed to the refund notice, when it was paid, and when each PDF was delivered.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> adminDetail(Long orderId) {
        Order order = orders.findById(orderId).orElseThrow(ApiException::notFound);
        Map<String, Object> body = adminView(order);
        body.put("paid_at", order.paidAt == null ? null : order.paidAt.toString());
        body.put("refund_notice_agreed_at",
                order.refundNoticeAgreedAt == null ? null : order.refundNoticeAgreedAt.toString());
        addDownloads(body);
        return body;
    }

    private static Map<String, Object> adminView(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", order.id);
        body.put("user_id", order.userId);
        body.put("status", order.status);
        body.put("amount", order.amount);
        body.put("pg_order_id", order.pgOrderId);
        body.put("confirm_started_at", order.confirmStartedAt == null ? null : order.confirmStartedAt.toString());
        body.put("created_at", order.createdAt.toString());
        return body;
    }

    // --- refund requests: the buyer asks, an admin reads the reason and decides ---

    private static final org.springframework.jdbc.core.RowMapper<Map<String, Object>> REQUEST_ROW = (row, index) -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.getLong("id"));
        body.put("order_id", row.getLong("order_id"));
        body.put("user_id", row.getLong("user_id"));
        body.put("email", row.getString("email"));
        body.put("reason", row.getString("reason"));
        body.put("status", row.getString("status"));
        body.put("admin_note", row.getString("admin_note"));
        body.put("amount", row.getInt("amount"));
        body.put("paid_at", iso(row.getObject("paid_at", LocalDateTime.class)));
        body.put("refund_notice_agreed_at", iso(row.getObject("refund_notice_agreed_at", LocalDateTime.class)));
        body.put("created_at", iso(row.getObject("created_at", LocalDateTime.class)));
        body.put("decided_at", iso(row.getObject("decided_at", LocalDateTime.class)));
        return body;
    };
    private static final String REQUEST_SELECT = "select r.id, r.order_id, r.user_id, u.email, r.reason, r.status,"
            + " r.admin_note, r.created_at, r.decided_at, o.amount, o.paid_at, o.refund_notice_agreed_at"
            + " from refund_requests r"
            + " join users u on u.id = r.user_id join orders o on o.id = r.order_id";

    // Stored times are UTC wall-clock values; they are read back as such, never through this machine's zone.
    private static String iso(LocalDateTime utcTime) {
        return utcTime == null ? null : utcTime + "Z";
    }

    /** Refund requests newest first, optionally of one status. Each carries what the admin needs to decide. */
    @Transactional(readOnly = true)
    public Map<String, Object> refundRequests(String status, int page, int pageSize) {
        String where = status == null ? "" : " where r.status = ?";
        List<Object> filter = status == null ? List.of() : List.of(status);
        long count = jdbc.sql("select count(*) from refund_requests r" + where).params(filter).query(Long.class).single();
        List<Object> paged = new ArrayList<>(filter);
        paged.add(pageSize);
        paged.add((long) (page - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.sql(REQUEST_SELECT + where + " order by r.id desc limit ? offset ?")
                .params(paged).query(REQUEST_ROW).list();
        rows.forEach(this::addDownloads);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        body.put("results", rows);
        return body;
    }

    private Map<String, Object> refundRequest(Long requestId) {
        Map<String, Object> request = jdbc.sql(REQUEST_SELECT + " where r.id = ?").param(requestId)
                .query(REQUEST_ROW).optional().orElseThrow(ApiException::notFound);
        addDownloads(request);
        return request;
    }

    // Whether, how often and when the buyer took each PDF since paying for this order. A last
    // download after the request date means the buyer kept downloading while asking for the money back.
    private void addDownloads(Map<String, Object> request) {
        request.put("downloads", jdbc.sql("select i.activity_id, i.title, i.plan, count(d.id) as download_count,"
                        + " min(d.downloaded_at) as first_downloaded_at, max(d.downloaded_at) as last_downloaded_at"
                        + " from order_items i join orders o on o.id = i.order_id"
                        + " left join pdf_downloads d on d.activity_id = i.activity_id and d.user_id = o.user_id"
                        + " and d.downloaded_at >= o.paid_at"
                        + " where i.order_id = ? group by i.activity_id, i.title, i.plan order by i.activity_id")
                .param(request.get("order_id"))
                .query((row, index) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("activity_id", row.getLong("activity_id"));
                    item.put("title", row.getString("title"));
                    item.put("plan", row.getString("plan"));
                    item.put("download_count", row.getLong("download_count"));
                    item.put("first_downloaded_at", iso(row.getObject("first_downloaded_at", LocalDateTime.class)));
                    item.put("last_downloaded_at", iso(row.getObject("last_downloaded_at", LocalDateTime.class)));
                    return item;
                }).list());
    }

    /**
     * Approves a request: the money goes back at the payment company, and the request is marked
     * approved in the same place the order becomes refunded (settle), whoever triggers that.
     */
    public Map<String, Object> approveRefund(Long requestId) {
        Map<String, Object> request = refundRequest(requestId);
        if (!"requested".equals(request.get("status"))) {
            throw new ApiException(409, "INVALID_STATE", "이미 처리된 환불 요청입니다.");
        }
        // The buyer's own words stay with us; the payment company gets a fixed reason (its limit is 200 characters).
        refund((Long) request.get("order_id"), "고객 환불 요청");
        // refund() returns only once the order is refunded. If another admin rejected the request
        // while the payment company was being called, the money still went back, and the request says so.
        jdbc.sql("update refund_requests set status = 'approved', admin_note = null, decided_at = ?"
                + " where id = ? and status = 'rejected'").params(utc(Instant.now()), requestId).update();
        return refundRequest(requestId);
    }

    @Transactional
    public Map<String, Object> rejectRefund(Long requestId, String note) {
        refundRequest(requestId);
        int changed = jdbc.sql("update refund_requests set status = 'rejected', admin_note = ?, decided_at = ?"
                + " where id = ? and status = 'requested'").params(note, utc(Instant.now()), requestId).update();
        if (changed == 0) {
            throw new ApiException(409, "INVALID_STATE", "이미 처리된 환불 요청입니다.");
        }
        return refundRequest(requestId);
    }

    /**
     * The buyer's own cancel. An order nobody has started paying is simply cancelled. A paid order
     * cannot be undone by the buyer: the reason is stored as a refund request for an admin.
     */
    @Transactional
    public Map<String, Object> cancel(Long userId, Long orderId, String reason) {
        // The buyer's row first, as everywhere: a member cannot leave between this and the request being stored.
        lockLiveUser(userId);
        Order order = orders.lockById(orderId).filter(found -> found.userId.equals(userId))
                .orElseThrow(ApiException::notFound);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", order.id);
        if (order.status.equals(Order.PAID)) {
            if (order.amount == 0) {
                throw new ApiException(409, "INVALID_STATE", "무료 주문은 환불 대상이 아닙니다.");
            }
            // The order's row lock makes this check and the insert one step: one open request per order.
            if (jdbc.sql("select count(*) from refund_requests where order_id = ? and status = 'requested'")
                    .param(order.id).query(Long.class).single() > 0) {
                throw new ApiException(409, "INVALID_STATE", "이미 환불을 요청했습니다.");
            }
            jdbc.sql("insert into refund_requests (order_id, user_id, reason, status, created_at)"
                    + " values (?, ?, ?, 'requested', ?)").params(order.id, userId, reason, utc(Instant.now())).update();
            body.put("status", order.status);
            body.put("refund_amount", 0);
            body.put("refund_request", "requested");
            return body;
        }
        if (!order.status.equals(Order.PENDING) || order.confirmStartedAt != null) {
            throw new ApiException(409, "INVALID_STATE", "취소할 수 없는 주문입니다.");
        }
        order.status = Order.CANCELLED;
        body.put("status", order.status);
        body.put("refund_amount", 0);
        body.put("refund_request", null);
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Long userId, int page, int pageSize) {
        Page<Order> found = orders.findByUserIdOrderByIdDesc(userId, PageRequest.of(page - 1, pageSize));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", found.getTotalElements());
        body.put("results", found.getContent().stream().map(OrderService::summary).toList());
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long userId, Long orderId) {
        Order order = orders.findByIdAndUserId(orderId, userId).orElseThrow(ApiException::notFound);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Order.Item item : order.items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("activity_id", item.activityId());
            row.put("title", item.title());
            row.put("price", item.price());
            row.put("plan", item.plan());
            items.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.id);
        body.put("status", order.status);
        body.put("items", items);
        body.put("total_amount", order.amount);
        // The buyer's latest refund request, with the admin's answer once there is one. Null if none.
        body.put("refund_request", jdbc.sql("select status, reason, admin_note from refund_requests"
                        + " where order_id = ? order by id desc limit 1").param(order.id)
                .query((row, index) -> {
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("status", row.getString("status"));
                    request.put("reason", row.getString("reason"));
                    request.put("admin_note", row.getString("admin_note"));
                    return request;
                }).optional().orElse(null));
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> receipt(Long userId, Long orderId) {
        Order order = orders.findByIdAndUserId(orderId, userId).orElseThrow(ApiException::notFound);
        if (order.receiptUrl == null) {
            throw new ApiException(409, "INVALID_STATE", "영수증이 없는 주문입니다.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receipt_url", order.receiptUrl);
        body.put("order_id", order.id);
        return body;
    }

    /**
     * What the user bought, newest first. An activity stays listed after it is unpublished, and a
     * 7-day pass stays listed after its days are over, marked expired. Refunded ones are gone.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> purchases(Long userId, int page, int pageSize) {
        long count = jdbc.sql("select count(*) from purchase_entitlements where user_id = ? and status = 'active'")
                .param(userId).query(Long.class).single();
        Map<Long, LocalDateTime> expiry = new LinkedHashMap<>();   // null: owned for good
        jdbc.sql("select activity_id, expires_at from purchase_entitlements where user_id = ? and status = 'active'"
                        + " order by id desc limit ? offset ?")
                .params(userId, pageSize, (long) (page - 1) * pageSize)
                .query((org.springframework.jdbc.core.RowCallbackHandler) row ->
                        expiry.put(row.getLong("activity_id"), row.getObject("expires_at", LocalDateTime.class)));
        LocalDateTime now = utc(Instant.now());
        List<Map<String, Object>> results = activities.viewsOfOwned(new ArrayList<>(expiry.keySet()));
        for (Map<String, Object> view : results) {
            LocalDateTime until = expiry.get(view.get("id"));
            view.put("plan", until == null ? PERMANENT : RENTAL);
            view.put("expires_at", iso(until));
            view.put("expired", until != null && !until.isAfter(now));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        body.put("results", results);
        return body;
    }

    /**
     * A short-lived link to the member's own copy of the PDF, with their code printed on it: for
     * whoever holds the right at this moment, or for anyone signed in when the activity is free and
     * public. The same rule is applied again when the file is sent.
     * No transaction: the first request stores the member's code and writes the copy, and a file
     * is not made while a database transaction is held open.
     */
    public Map<String, Object> download(Long userId, Long activityId) {
        Activity activity = activities.find(activityId).orElseThrow(ApiException::notFound);
        switch (activities.downloadState(userId, activity)) {
            case "ok":
                return issuances.link(userId, activity);
            case "expired":
                throw new ApiException(403, "ACCESS_EXPIRED", "이용 기간이 끝난 활동지입니다.");
            case "refund":
                throw new ApiException(409, "INVALID_STATE", "환불 요청을 처리하는 동안에는 받을 수 없습니다.");
            default:
                if (!activity.published()) {
                    throw ApiException.notFound();
                }
                throw new ApiException(403, "PERMISSION_DENIED", "구매한 활동지만 받을 수 있습니다.");
        }
    }

    // For what the buyer asks for: the account may have been closed (or the member may have left)
    // while this request waited for the row.
    private void lockLiveUser(Long userId) {
        lockUser(userId);
        if (jdbc.sql("select count(*) from users where id = ? and is_active = true").param(userId).query(Long.class).single() == 0) {
            throw new ApiException(401, "AUTHENTICATION_REQUIRED", "요청을 처리할 수 없습니다.");
        }
    }

    // One order flow at a time for a user: create and confirm queue up on this row.
    private void lockUser(Long userId) {
        jdbc.sql("select id from users where id = ? for update").param(userId).query(Long.class).single();
    }

    private static Map<String, Object> summary(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_id", order.id);
        body.put("status", order.status);
        body.put("amount", order.amount);
        // The order number to hand to the payment window. Only an order still to be paid has one.
        body.put("payment_session_token", order.status.equals(Order.PENDING) ? order.pgOrderId : null);
        return body;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
