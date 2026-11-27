package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import club.yippee.api.order.PaymentGateway.Payment;
import club.yippee.api.order.PaymentGateway.State;

/** Orders and payment against a stand-in payment company. Each test is one rule that protects money. */
class OrderApiTest extends ApiTestBase {

    private static final String ORDERS = "/api/v1/orders";
    private static final String CONFIRM = "/api/v1/payments/confirm";
    private static final String WEBHOOK = "/api/v1/payments/webhook";
    private Session buyer;
    private Session other;
    private long sheet;

    @BeforeEach
    void accountsAndOnePaidActivity() throws Exception {
        buyer = signUp("buyer@example.com");
        other = signUp("other@example.com");
        sheet = activity("유료 활동지", 1900, true);
        Path folder = Path.of("build/test-files/activities");
        Files.createDirectories(folder);
        Files.write(folder.resolve("play.pdf"), samplePdf(1));
        Files.write(folder.resolve("cover.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }

    private long activity(String title, int price, boolean published) {
        jdbc.sql("insert into activities (title, description, age_min_months, age_max_months, difficulty, duration_min,"
                        + " duration_max, price, image_path, pdf_path, is_public, created_at, updated_at)"
                        + " values (?, '설명', 60, 65, 'easy', 5, 5, ?, 'activities/cover.png', 'activities/play.pdf', ?, ?, ?)")
                .params(title, price, published, utc(Instant.now()), utc(Instant.now())).update();
        return count("select max(id) from activities");
    }

    private ResultActions order(Session who, long... ids) throws Exception {
        String list = java.util.Arrays.toString(ids);
        return post(ORDERS, who.access(), "{\"activity_ids\":" + list + AGREED + "}");
    }

    /** What every buyer ticks: the payment itself, and the notice that a refund is limited after a download. */
    private static final String AGREED = ",\"agreed_to_payment\":true,\"agreed_to_refund_notice\":true";

    /** Creates an order and returns {order id, payment company order number}. */
    private String[] pending(Session who, long... ids) throws Exception {
        ResultActions created = order(who, ids).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending"));
        Number id = read(created, "$.order_id");
        return new String[]{id.toString(), read(created, "$.payment_session_token")};
    }

    private ResultActions confirm(Session who, String[] order, String paymentKey) throws Exception {
        return post(CONFIRM, who.access(), q("{'order_id':%s,'pg_payment_key':'%s','pg_order_id':'%s'}"
                .formatted(order[0], paymentKey, order[1])));
    }

    private static Payment done(long amount) {
        return new Payment(State.DONE, amount, "pk_1", "https://receipt.example/1", "DONE");
    }

    private long entitlements(Session who) {
        return count("select count(*) from purchase_entitlements where user_id = ? and status = 'active'", who.userId());
    }

    private String orderStatus(String[] order) {
        return jdbc.sql("select status from orders where id = ?").param(Long.valueOf(order[0])).query(String.class).single();
    }

    @Test
    void theQuoteAddsUpStoredPricesAndNamesWhatCannotBeBought() throws Exception {
        long second = activity("두 번째", 990, true);
        long hidden = activity("비공개", 500, false);
        post(ORDERS + "/quote", buyer.access(), "{\"activity_ids\":[" + sheet + "," + second + "," + hidden + ",999999]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.refund_notice").value(org.hamcrest.Matchers.containsString("제17조")))
                .andExpect(jsonPath("$.subtotal").value(2890)).andExpect(jsonPath("$.payable_amount").value(2890))
                .andExpect(jsonPath("$.unavailable_activity_ids[0]").value(hidden))
                .andExpect(jsonPath("$.unavailable_activity_ids[1]").value(999999));
        expectError(post(ORDERS + "/quote", null, "{\"activity_ids\":[" + sheet + "]}"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void anOrderForFreeActivitiesIsCompleteWithoutThePaymentCompany() throws Exception {
        long free = activity("무료 활동지", 0, true);
        order(buyer, free).andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.status").value("paid")).andExpect(jsonPath("$.amount").value(0))
                .andExpect(jsonPath("$.payment_session_token").isEmpty());
        assertThat(entitlements(buyer)).isEqualTo(1);
        verify(gateway, never()).confirm(anyString(), anyString(), anyLong());
        // A free order is complete the moment it is made, and says that no payment company was involved.
        get(ORDERS, buyer.access()).andExpect(jsonPath("$.results[0].payment_method").value("free"))
                .andExpect(jsonPath("$.results[0].paid_at").isString());
        get("/api/v1/purchases/activities", buyer.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].id").value(free));
        // Nothing is paid, so there is no refund notice to agree to.
        post(ORDERS, other.access(), "{\"activity_ids\":[" + free + "],\"agreed_to_payment\":true}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("paid"));
    }

    @Test
    void payingGrantsTheActivityOnceHoweverOftenConfirmIsCalled() throws Exception {
        long second = activity("두 번째", 990, true);
        post("/api/v1/cart/items", buyer.access(), "{\"activity_id\":" + sheet + "}").andExpect(status().isCreated());
        String[] order = pending(buyer, sheet, second);
        assertThat(order[1]).startsWith("ord_").hasSizeBetween(6, 64);
        when(gateway.confirm("pk_1", order[1], 2890)).thenReturn(done(2890));

        confirm(buyer, order, "pk_1").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.order_id").value(Integer.valueOf(order[0])))
                .andExpect(jsonPath("$.status").value("paid")).andExpect(jsonPath("$.paid_amount").value(2890));
        confirm(buyer, order, "pk_1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("paid"));
        // The amount sent to the payment company is ours; the confirm request carries none.
        verify(gateway, times(1)).confirm("pk_1", order[1], 2890);
        assertThat(entitlements(buyer)).isEqualTo(2);
        assertThat(entitlements(other)).isZero();

        get("/api/v1/cart", buyer.access()).andExpect(jsonPath("$.items").isEmpty());
        expectError(post("/api/v1/cart/items", buyer.access(), "{\"activity_id\":" + sheet + "}"), 409, "ALREADY_PURCHASED");
        expectError(order(buyer, sheet), 409, "ALREADY_PURCHASED");
        // An order still to be paid has a date of its making, and neither a payment date nor a way of paying.
        String[] waiting = pending(other, second);
        get(ORDERS + "/" + waiting[0], other.access()).andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.paid_at").isEmpty()).andExpect(jsonPath("$.refunded_at").isEmpty())
                .andExpect(jsonPath("$.payment_method").isEmpty());
        get(ORDERS + "/" + order[0], buyer.access()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.created_at").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.paid_at").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.refunded_at").isEmpty())
                .andExpect(jsonPath("$.payment_method").value("toss"))
                .andExpect(jsonPath("$.refund_request").isEmpty())
                .andExpect(jsonPath("$.status").value("paid")).andExpect(jsonPath("$.total_amount").value(2890))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].price").value(1900));
        get(ORDERS + "/" + order[0] + "/receipt", buyer.access())
                .andExpect(jsonPath("$.receipt_url").value("https://receipt.example/1"));
        // The list can start at a Korean day, counted from when each order was made.
        jdbc.sql("update orders set created_at = ? where id = ?")
                .params(java.time.LocalDateTime.parse("2026-11-24T14:59:59"), Long.valueOf(order[0])).update();
        get(ORDERS + "?since=2026-11-24", buyer.access()).andExpect(jsonPath("$.count").value(1));
        get(ORDERS + "?since=2026-11-25", buyer.access()).andExpect(jsonPath("$.count").value(0));
        for (String bad : new String[]{"?since=2026-11", "?since=2026-02-30", "?since=yesterday", "?until=2026-11-25"}) {
            expectError(get(ORDERS + bad, buyer.access()), 400, "VALIDATION_ERROR");
        }
        get(ORDERS, buyer.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].status").value("paid"))
                .andExpect(jsonPath("$.results[0].created_at").isString()).andExpect(jsonPath("$.results[0].paid_at").isString())
                .andExpect(jsonPath("$.results[0].payment_method").value("toss"))
                .andExpect(jsonPath("$.results[0].payment_session_token").isEmpty());
        get("/api/v1/purchases/activities", buyer.access()).andExpect(jsonPath("$.count").value(2));
    }

    private static final String REQUESTS = "/api/v1/admin/orders/refund-requests";

    /** A paid order for the one paid activity. Returns {order id, payment company order number}. */
    private String[] paid(Session who, String paymentKey) throws Exception {
        String[] order = pending(who, sheet);
        when(gateway.confirm(eq(paymentKey), anyString(), anyLong()))
                .thenReturn(new Payment(State.DONE, 1900, paymentKey, null, "DONE"));
        confirm(who, order, paymentKey).andExpect(status().isOk());
        return order;
    }

    private static final int PASS_PRICE = 700;

    private ResultActions rent(Session who) throws Exception {
        return post(ORDERS, who.access(), "{\"activity_ids\":[" + sheet + "],\"rental_activity_ids\":[" + sheet + "]" + AGREED + "}");
    }

    /** A paid 7-day pass for the one paid activity. Returns {order id, payment company order number}. */
    private String[] rented(Session who, String paymentKey) throws Exception {
        ResultActions created = rent(who).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(PASS_PRICE));
        Number id = read(created, "$.order_id");
        String[] order = {id.toString(), read(created, "$.payment_session_token")};
        when(gateway.confirm(eq(paymentKey), anyString(), anyLong()))
                .thenReturn(new Payment(State.DONE, PASS_PRICE, paymentKey, null, "DONE"));
        confirm(who, order, paymentKey).andExpect(status().isOk());
        return order;
    }

    private String link(Session who) throws Exception {
        String url = read(post("/api/v1/activities/" + sheet + "/download", who.access(), null).andExpect(status().isOk()),
                "$.download_url");
        return url.replace("http://localhost", "");
    }

    @Test
    void aSevenDayPassCostsLessAndEndsOnTheServer() throws Exception {
        String download = "/api/v1/activities/" + sheet + "/download";
        String owned = "/api/v1/purchases/activities";
        String asked = "{\"activity_ids\":[" + sheet + "],\"rental_activity_ids\":[" + sheet + "]}";
        // A sheet without a 7-day price is sold for good only.
        expectError(rent(buyer), 400, "VALIDATION_ERROR");
        post(ORDERS + "/quote", buyer.access(), asked).andExpect(jsonPath("$.unavailable_activity_ids[0]").value(sheet));
        jdbc.sql("update activities set rental_price = ? where id = ?").params(PASS_PRICE, sheet).update();
        get("/api/v1/activities/" + sheet, null).andExpect(jsonPath("$.price").value(1900))
                .andExpect(jsonPath("$.rental_price").value(PASS_PRICE)).andExpect(jsonPath("$.rental_days").value(7));
        post(ORDERS + "/quote", buyer.access(), asked).andExpect(jsonPath("$.payable_amount").value(PASS_PRICE))
                .andExpect(jsonPath("$.unavailable_activity_ids").isEmpty());
        for (String bad : List.of("[999999]", "[" + sheet + "," + sheet + "]", "[\"" + sheet + "\"]", "null", "7")) {
            expectError(post(ORDERS, buyer.access(), "{\"activity_ids\":[" + sheet + "],\"rental_activity_ids\":" + bad + AGREED + "}"),
                    400, "VALIDATION_ERROR");
        }

        // The pass is charged at its own price and runs 7 days from the moment the payment is settled.
        String[] order = rented(buyer, "pk_r");
        verify(gateway).confirm("pk_r", order[1], PASS_PRICE);
        get(ORDERS + "/" + order[0], buyer.access()).andExpect(jsonPath("$.items[0].plan").value("rental"))
                .andExpect(jsonPath("$.items[0].price").value(PASS_PRICE));
        ResultActions listed = get(owned, buyer.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].plan").value("rental")).andExpect(jsonPath("$.results[0].expired").value(false));
        String until = read(listed, "$.results[0].expires_at");
        Instant week = Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS);
        assertThat(Instant.parse(until)).isBetween(week.minusSeconds(60), week.plusSeconds(60));
        String link = link(buyer);
        get(link, null).andExpect(status().isOk());

        // While it runs: no second pass, but the sheet can still go into the cart to be bought for good.
        expectError(rent(buyer), 409, "ALREADY_PURCHASED");
        post(ORDERS + "/quote", buyer.access(), asked).andExpect(jsonPath("$.unavailable_activity_ids[0]").value(sheet));
        post("/api/v1/cart/items", buyer.access(), "{\"activity_id\":" + sheet + "}").andExpect(status().isCreated());

        // After 7 days the server hands the file out no more, not even through a link made earlier,
        // and the buyer's list says the pass has ended.
        jdbc.sql("update purchase_entitlements set expires_at = ? where user_id = ?")
                .params(utc(Instant.now().minusSeconds(1)), buyer.userId()).update();
        expectError(post(download, buyer.access(), null), 403, "ACCESS_EXPIRED");
        expectError(get(link, null), 404, "NOT_FOUND");
        get(owned, buyer.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].plan").value("rental")).andExpect(jsonPath("$.results[0].expired").value(true));
        assertThat(count("select count(*) from pdf_downloads")).isEqualTo(1);

        // An ended pass can be taken again; the one row for this buyer and sheet runs anew.
        rented(buyer, "pk_r2");
        get(owned, buyer.access()).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.results[0].expired").value(false));
        get(link(buyer), null).andExpect(status().isOk());

        // A running pass can be turned into ownership; after that nothing more can be bought.
        String[] forGood = paid(buyer, "pk_p");
        verify(gateway).confirm("pk_p", forGood[1], 1900);
        get(owned, buyer.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].plan").value("permanent"))
                .andExpect(jsonPath("$.results[0].expires_at").isEmpty()).andExpect(jsonPath("$.results[0].expired").value(false));
        get(link(buyer), null).andExpect(status().isOk());
        expectError(rent(buyer), 409, "ALREADY_PURCHASED");
        expectError(order(buyer, sheet), 409, "ALREADY_PURCHASED");
        expectError(post("/api/v1/cart/items", buyer.access(), "{\"activity_id\":" + sheet + "}"), 409, "ALREADY_PURCHASED");
        assertThat(count("select count(*) from purchase_entitlements where user_id = ?", buyer.userId())).isEqualTo(1);
    }

    @Test
    void aLinkIsCheckedAgainWhenTheFileLeavesAndNothingLeavesWhileARefundIsAsked() throws Exception {
        Session admin = signUp("admin@example.com");
        String download = "/api/v1/activities/" + sheet + "/download";
        String[] order = paid(buyer, "pk_1");
        String link = link(buyer);

        // While a refund request waits, nothing is handed out: what the admin reads cannot change under them.
        post(ORDERS + "/" + order[0] + "/cancel", buyer.access(), q("{'reason':'환불 부탁드립니다'}")).andExpect(status().isCreated());
        expectError(post(download, buyer.access(), null), 409, "INVALID_STATE");
        expectError(get(link, null), 404, "NOT_FOUND");
        Number request = read(get(REQUESTS, admin.access()), "$.results[0].id");
        get(REQUESTS, admin.access()).andExpect(jsonPath("$.results[0].downloads[0].download_count").value(0))
                .andExpect(jsonPath("$.results[0].downloads[0].plan").value("permanent"));
        post(REQUESTS + "/" + request + "/reject", admin.access(), q("{'note':'다시 받아 보세요'}")).andExpect(status().isOk());
        get(link, null).andExpect(status().isOk());

        // A link made before a refund dies with the right.
        when(gateway.cancel(eq("pk_1"), anyString())).thenReturn(Payment.of(State.CANCELED, "CANCELED"));
        when(gateway.lookup(order[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_1", null, "CANCELED"));
        post("/api/v1/admin/orders/" + order[0] + "/refund", admin.access(), q("{'reason':'합의 환불'}")).andExpect(status().isOk());
        expectError(get(link, null), 404, "NOT_FOUND");
        assertThat(count("select count(*) from pdf_downloads")).isEqualTo(1);
        // The refund has its own time: the sales chart counts it on that day, not on the day of the sale.
        assertThat(count("select count(*) from orders where status = 'refunded' and refunded_at >= paid_at")).isEqualTo(1);

        // So does the link of an account that was closed.
        paid(other, "pk_2");
        String theirs = link(other);
        patch("/api/v1/admin/users/" + other.userId(), admin.access(), q("{'is_active':false}")).andExpect(status().isOk());
        expectError(get(theirs, null), 404, "NOT_FOUND");

        // Asking for one sheet's link over and over is cut off for the hour.
        long another = activity("다른 유료", 500, true);
        seedLimit("download", buyer.userId() + ":" + another, 10);
        expectError(post("/api/v1/activities/" + another + "/download", buyer.access(), null), 429, "RATE_LIMITED");
    }

    @Test
    void aBuyerAsksForARefundAndAnAdminDecides() throws Exception {
        Session admin = signUp("admin@example.com");
        String[] order = paid(buyer, "pk_1");
        String cancel = ORDERS + "/" + order[0] + "/cancel";
        String link = ((String) read(post("/api/v1/activities/" + sheet + "/download", buyer.access(), null),
                "$.download_url")).replace("http://localhost", "");
        get(link, null).andExpect(status().isOk());

        // Asking moves no money and takes no right away. One open request per order.
        post(cancel, buyer.access(), q("{'reason':'아이에게 맞지 않아요'}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.refund_amount").value(0)).andExpect(jsonPath("$.refund_request").value("requested"));
        assertThat(entitlements(buyer)).isEqualTo(1);
        verify(gateway, never()).cancel(anyString(), anyString());
        expectError(post(cancel, buyer.access(), q("{'reason':'again'}")), 409, "INVALID_STATE");
        expectError(post(cancel, other.access(), q("{'reason':'not mine'}")), 404, "NOT_FOUND");
        expectError(post(cancel, buyer.access(), "{}"), 400, "VALIDATION_ERROR");
        get(ORDERS + "/" + order[0], buyer.access()).andExpect(jsonPath("$.refund_request.status").value("requested"));

        // The admin reads the reason beside what was downloaded. Times are real UTC times, not this machine's clock.
        expectError(get(REQUESTS, buyer.access()), 403, "PERMISSION_DENIED");
        expectError(get(REQUESTS + "?status=unknown", admin.access()), 400, "VALIDATION_ERROR");
        ResultActions listed = get(REQUESTS + "?status=requested", admin.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].reason").value("아이에게 맞지 않아요"))
                .andExpect(jsonPath("$.results[0].email").value("buyer@example.com"))
                .andExpect(jsonPath("$.results[0].amount").value(1900))
                .andExpect(jsonPath("$.results[0].downloads.length()").value(1))
                .andExpect(jsonPath("$.results[0].downloads[0].download_count").value(1));
        for (String time : List.of("created_at", "paid_at", "refund_notice_agreed_at", "downloads[0].first_downloaded_at",
                "downloads[0].last_downloaded_at")) {
            String stored = read(listed, "$.results[0]." + time);
            assertThat(Instant.parse(stored)).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        }
        Number first = read(listed, "$.results[0].id");

        // The same record is on the order itself, for a dispute that comes without a request (a card chargeback).
        String evidence = "/api/v1/admin/orders/" + order[0];
        ResultActions shown = get(evidence, admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid")).andExpect(jsonPath("$.downloads[0].download_count").value(1));
        for (String time : List.of("paid_at", "refund_notice_agreed_at", "downloads[0].last_downloaded_at")) {
            String stored = read(shown, "$." + time);
            assertThat(Instant.parse(stored)).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        }
        expectError(get(evidence, buyer.access()), 403, "PERMISSION_DENIED");
        expectError(get("/api/v1/admin/orders/999999", admin.access()), 404, "NOT_FOUND");

        // Rejected: nothing moves, the buyer reads the answer and may ask again.
        expectError(post(REQUESTS + "/" + first + "/reject", admin.access(), "{}"), 400, "VALIDATION_ERROR");
        expectError(post(REQUESTS + "/" + first + "/reject", buyer.access(), q("{'note':'mine'}")), 403, "PERMISSION_DENIED");
        post(REQUESTS + "/" + first + "/reject", admin.access(), q("{'note':'이미 내려받은 자료입니다'}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected"));
        expectError(post(REQUESTS + "/" + first + "/reject", admin.access(), q("{'note':'again'}")), 409, "INVALID_STATE");
        expectError(post(REQUESTS + "/" + first + "/approve", admin.access(), null), 409, "INVALID_STATE");
        get(ORDERS + "/" + order[0], buyer.access()).andExpect(jsonPath("$.refund_request.status").value("rejected"))
                .andExpect(jsonPath("$.refund_request.admin_note").value("이미 내려받은 자료입니다"));
        assertThat(entitlements(buyer)).isEqualTo(1);
        verify(gateway, never()).cancel(anyString(), anyString());

        // Approved: the money goes back at the payment company, and only its own answer closes the request.
        post(cancel, buyer.access(), q("{'reason':'파일이 열리지 않아요'}")).andExpect(status().isCreated());
        Number second = read(get(REQUESTS + "?status=requested", admin.access()), "$.results[0].id");
        when(gateway.cancel(eq("pk_1"), anyString())).thenReturn(Payment.of(State.CANCELED, "CANCELED"));
        expectError(post(REQUESTS + "/" + second + "/approve", admin.access(), null), 503, "SERVICE_UNAVAILABLE");
        get(REQUESTS + "?status=requested", admin.access()).andExpect(jsonPath("$.count").value(1));
        assertThat(entitlements(buyer)).isEqualTo(1);
        when(gateway.lookup(order[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_1", null, "CANCELED"));
        post(REQUESTS + "/" + second + "/approve", admin.access(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.decided_at").isString());
        assertThat(orderStatus(order)).isEqualTo("refunded");
        assertThat(entitlements(buyer)).isZero();
        // The buyer's words never leave for the payment company.
        verify(gateway, never()).cancel("pk_1", "파일이 열리지 않아요");
        expectError(post(REQUESTS + "/" + second + "/reject", admin.access(), q("{'note':'late'}")), 409, "INVALID_STATE");
        expectError(post(REQUESTS + "/999999/approve", admin.access(), null), 404, "NOT_FOUND");
        expectError(post(cancel, buyer.access(), q("{'reason':'once more'}")), 409, "INVALID_STATE");
        get(REQUESTS, admin.access()).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void aRefundRequestFollowsTheMoneyWhoeverMovedIt() throws Exception {
        Session admin = signUp("admin@example.com");
        // Refunded on the payment company's own screen while a request waits: the webhook closes the request.
        String[] order = paid(buyer, "pk_1");
        post(ORDERS + "/" + order[0] + "/cancel", buyer.access(), q("{'reason':'환불 부탁드립니다'}")).andExpect(status().isCreated());
        when(gateway.lookup(order[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_1", null, "CANCELED"));
        post(WEBHOOK, null, q("{'data':{'orderId':'%s'}}".formatted(order[1]))).andExpect(status().isOk());
        get(ORDERS + "/" + order[0], buyer.access()).andExpect(jsonPath("$.status").value("refunded"))
                .andExpect(jsonPath("$.refund_request.status").value("approved"));

        // Another admin rejects while the refund is at the payment company: the money went back, so it reads approved.
        String[] next = paid(other, "pk_2");
        post(ORDERS + "/" + next[0] + "/cancel", other.access(), q("{'reason':'환불 부탁드립니다'}")).andExpect(status().isCreated());
        Number waiting = read(get(REQUESTS + "?status=requested", admin.access()), "$.results[0].id");
        when(gateway.lookup(next[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_2", null, "CANCELED"));
        when(gateway.cancel(eq("pk_2"), anyString())).thenAnswer(call -> {
            jdbc.sql("update refund_requests set status = 'rejected', admin_note = 'no' where id = ?").param(waiting).update();
            return Payment.of(State.CANCELED, "CANCELED");
        });
        post(REQUESTS + "/" + waiting + "/approve", admin.access(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.admin_note").isEmpty());
        assertThat(orderStatus(next)).isEqualTo("refunded");

        // A free order has nothing to refund.
        long free = activity("무료 활동지", 0, true);
        Number freeOrder = read(order(buyer, free).andExpect(status().isCreated()), "$.order_id");
        expectError(post(ORDERS + "/" + freeOrder + "/cancel", buyer.access(), q("{'reason':'x'}")), 409, "INVALID_STATE");
        assertThat(count("select count(*) from refund_requests")).isEqualTo(2);
    }

    @Test
    void aPaidAmountThatIsNotOursGrantsNothing() throws Exception {
        String[] order = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(done(100));
        expectError(confirm(buyer, order, "pk_1"), 409, "INVALID_STATE");
        assertThat(entitlements(buyer)).isZero();
        assertThat(orderStatus(order)).isEqualTo("review");
        // The buyer is not stuck: a new order can start. A full refund at the company closes the old one.
        pending(buyer, sheet);
        when(gateway.lookup(order[1])).thenReturn(new Payment(State.CANCELED, 100, "pk_1", null, "CANCELED"));
        post(WEBHOOK, null, q("{'data':{'orderId':'%s'}}".formatted(order[1]))).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("refunded");
        assertThat(entitlements(buyer)).isZero();
    }

    @Autowired
    private club.yippee.api.order.OrderService orderService;
    @Autowired
    private club.yippee.api.account.ProfileController housekeeping;

    @Test
    void theCatchUpJobSettlesWhatGotNoAnswerAndClosesWhatWasAbandoned() throws Exception {
        String[] stuck = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(Payment.of(State.UNKNOWN, "NO_ANSWER"));
        expectError(confirm(buyer, stuck, "pk_1"), 503, "SERVICE_UNAVAILABLE");
        String[] abandoned = pending(other, sheet);
        jdbc.sql("update orders set confirm_started_at = ? where id = ?")
                .params(utc(Instant.now().minusSeconds(300)), Long.valueOf(stuck[0])).update();
        jdbc.sql("update orders set created_at = ? where id = ?")
                .params(utc(Instant.now().minusSeconds(2 * 86400)), Long.valueOf(abandoned[0])).update();
        when(gateway.lookup(stuck[1])).thenReturn(done(1900));

        orderService.settleUnknown();
        assertThat(orderStatus(stuck)).isEqualTo("paid");
        assertThat(entitlements(buyer)).isEqualTo(1);
        assertThat(orderStatus(abandoned)).isEqualTo("cancelled");
        orderService.settleUnknown();
        assertThat(entitlements(buyer)).isEqualTo(1);

        // The nightly clean-up removes what can no longer be used and leaves the rest.
        seedLimit("login:ip", "203.0.113.1", 3);
        jdbc.sql("update auth_rate_limits set window_start = 0").update();
        jdbc.sql("update refresh_sessions set expires_at = ? where user_id = ?")
                .params(utc(Instant.now().minusSeconds(3 * 86400)), other.userId()).update();
        housekeeping.purgeExpired();
        assertThat(count("select count(*) from auth_rate_limits")).isZero();
        assertThat(count("select count(*) from refresh_sessions where user_id = ?", other.userId())).isZero();
        assertThat(count("select count(*) from refresh_sessions where user_id = ?", buyer.userId())).isEqualTo(1);
    }

    @Test
    void withoutAPaymentKeyNoPaidOrderIsStarted() throws Exception {
        when(gateway.ready()).thenReturn(false);
        expectError(order(buyer, sheet), 503, "SERVICE_UNAVAILABLE");
        assertThat(count("select count(*) from orders")).isZero();
        long free = activity("무료", 0, true);
        order(buyer, free).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("paid"));
    }

    @Test
    void aLatePaymentCannotSlipInWhileTheSameBuyerStartsAnotherOrder() throws Exception {
        String[] first = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(Payment.of(State.UNKNOWN, "NO_ANSWER"));
        expectError(confirm(buyer, first, "pk_1"), 503, "SERVICE_UNAVAILABLE");
        when(gateway.lookup(first[1])).thenReturn(done(1900));

        // In the middle of the second order being made, the webhook for the first one arrives.
        // It must wait for the buyer's row; otherwise the second order could be paid for too.
        AtomicBoolean settledDuringCreate = new AtomicBoolean();
        AtomicReference<Thread> webhook = new AtomicReference<>();
        doAnswer(call -> {
            if (webhook.get() == null) {
                Thread thread = new Thread(() -> {
                    try {
                        post(WEBHOOK, null, q("{'data':{'orderId':'%s'}}".formatted(first[1])));
                    } catch (Exception ignored) {
                        // The assertions below see whatever state this left.
                    }
                });
                webhook.set(thread);
                thread.start();
                thread.join(400);
                settledDuringCreate.set(!thread.isAlive());
            }
            return call.callRealMethod();
        }).when(catalogue).findPublic(sheet);

        expectError(order(buyer, sheet), 409, "INVALID_STATE");
        webhook.get().join(5000);
        assertThat(settledDuringCreate).isFalse();
        assertThat(orderStatus(first)).isEqualTo("paid");
        assertThat(entitlements(buyer)).isEqualTo(1);
        assertThat(count("select count(*) from orders where status = 'pending'")).isZero();
    }

    @Test
    void aRefusedPaymentEndsTheOrder() throws Exception {
        String[] order = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(Payment.of(State.FAILED, "REJECT_CARD_COMPANY"));
        ResultActions refused = confirm(buyer, order, "pk_1");
        expectError(refused, 409, "INVALID_STATE");
        assertThat(refused.andReturn().getResponse().getContentAsString()).doesNotContain("REJECT_CARD_COMPANY");
        expectError(confirm(buyer, order, "pk_2"), 409, "INVALID_STATE");
        verify(gateway, times(1)).confirm(anyString(), anyString(), anyLong());
        assertThat(orderStatus(order)).isEqualTo("payment_failed");
        assertThat(entitlements(buyer)).isZero();
        pending(buyer, sheet);
    }

    @Test
    void noAnswerLeavesTheOrderOpenUntilTheWebhookSettlesIt() throws Exception {
        String[] order = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(Payment.of(State.UNKNOWN, "NO_ANSWER"));
        expectError(confirm(buyer, order, "pk_1"), 503, "SERVICE_UNAVAILABLE");
        assertThat(orderStatus(order)).isEqualTo("pending");
        // The first payment may still go through, so a second order must not start.
        expectError(order(buyer, sheet), 409, "INVALID_STATE");
        expectError(confirm(buyer, order, "pk_other"), 409, "INVALID_STATE");
        expectError(post(ORDERS + "/" + order[0] + "/cancel", buyer.access(), q("{'reason':'x'}")), 409, "INVALID_STATE");

        String event = q("{'eventType':'PAYMENT_STATUS_CHANGED','createdAt':'2026-11-24T00:00:00.000000',"
                + "'data':{'orderId':'%s','status':'DONE','totalAmount':1}}".formatted(order[1]));
        expectError(post(WEBHOOK, null, event), 503, "SERVICE_UNAVAILABLE");
        // A forged "DONE": the body is not believed, and the company itself says the payment is not done.
        when(gateway.lookup(order[1])).thenReturn(Payment.of(State.PENDING, "IN_PROGRESS"));
        post(WEBHOOK, null, event).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));
        assertThat(entitlements(buyer)).isZero();

        when(gateway.lookup(order[1])).thenReturn(done(1900));
        post(WEBHOOK, null, event).andExpect(status().isOk());
        post(WEBHOOK, null, event).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("paid");
        assertThat(entitlements(buyer)).isEqualTo(1);
        confirm(buyer, order, "pk_1").andExpect(status().isOk()).andExpect(jsonPath("$.paid_amount").value(1900));

        post(WEBHOOK, null, q("{'eventType':'PAYMENT_STATUS_CHANGED','data':{'orderId':'ord_unknown'}}")).andExpect(status().isOk());
        for (String malformed : List.of("{}", "[]", "{'data':{}}", "{'data':{'orderId':5}}")) {
            expectError(post(WEBHOOK, null, q(malformed)), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void aRefundSeenAtThePaymentCompanyTakesTheRightAway() throws Exception {
        String[] order = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(done(1900));
        confirm(buyer, order, "pk_1").andExpect(status().isOk());
        when(gateway.lookup(order[1])).thenReturn(new Payment(State.REVIEW, 1900, "pk_1", null, "PARTIAL_CANCELED"));
        String event = q("{'data':{'orderId':'%s'}}".formatted(order[1]));
        post(WEBHOOK, null, event).andExpect(status().isOk());
        assertThat(entitlements(buyer)).isEqualTo(1);

        when(gateway.lookup(order[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_1", null, "CANCELED"));
        post(WEBHOOK, null, event).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("refunded");
        assertThat(entitlements(buyer)).isZero();
        expectError(post("/api/v1/activities/" + sheet + "/download", buyer.access(), null), 403, "PERMISSION_DENIED");
        // Buying again after a refund works and reuses the one row for this user and activity.
        String[] again = pending(buyer, sheet);
        when(gateway.confirm(eq("pk_2"), anyString(), anyLong())).thenReturn(done(1900));
        confirm(buyer, again, "pk_2").andExpect(status().isOk());
        assertThat(count("select count(*) from purchase_entitlements where user_id = ?", buyer.userId())).isEqualTo(1);
        assertThat(entitlements(buyer)).isEqualTo(1);
    }

    @Test
    void anOrderBelongsToItsBuyerAndANewOrderReplacesAnUnpaidOne() throws Exception {
        String[] first = pending(buyer, sheet);
        for (ResultActions closed : List.of(confirm(other, first, "pk_1"), get(ORDERS + "/" + first[0], other.access()),
                get(ORDERS + "/" + first[0] + "/receipt", other.access()),
                post(ORDERS + "/" + first[0] + "/cancel", other.access(), q("{'reason':'x'}")))) {
            expectError(closed, 404, "NOT_FOUND");
        }
        expectError(post(CONFIRM, buyer.access(), q("{'order_id':%s,'pg_payment_key':'pk_1','pg_order_id':'ord_wrong'}"
                .formatted(first[0]))), 400, "VALIDATION_ERROR");
        expectError(get(ORDERS + "/" + first[0] + "/receipt", buyer.access()), 409, "INVALID_STATE");

        String[] second = pending(buyer, sheet);
        assertThat(orderStatus(first)).isEqualTo("cancelled");
        expectError(confirm(buyer, first, "pk_1"), 409, "INVALID_STATE");
        post(ORDERS + "/" + second[0] + "/cancel", buyer.access(), q("{'reason':'changed_mind'}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.refund_amount").value(0)).andExpect(jsonPath("$.refund_request").isEmpty());
        verify(gateway, never()).confirm(anyString(), anyString(), anyLong());
        get(ORDERS, other.access()).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void anUnsettledOrderBlocksOnlyOrdersForTheSameActivity() throws Exception {
        long second = activity("두 번째", 990, true);
        String[] first = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(Payment.of(State.UNKNOWN, "NO_ANSWER"));
        expectError(confirm(buyer, first, "pk_1"), 503, "SERVICE_UNAVAILABLE");
        // Anything that includes the unsettled activity is refused; something else is not.
        expectError(order(buyer, sheet, second), 409, "INVALID_STATE");
        String[] elsewhere = pending(buyer, second);
        assertThat(orderStatus(first)).isEqualTo("pending");
        when(gateway.confirm(eq("pk_2"), anyString(), anyLong())).thenReturn(done(990));
        confirm(buyer, elsewhere, "pk_2").andExpect(status().isOk());
        assertThat(entitlements(buyer)).isEqualTo(1);
    }

    @Test
    void theCatchUpJobSendsAgainAConfirmThatNeverArrived() throws Exception {
        String[] stuck = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(Payment.of(State.UNKNOWN, "NO_ANSWER"));
        expectError(confirm(buyer, stuck, "pk_1"), 503, "SERVICE_UNAVAILABLE");
        jdbc.sql("update orders set confirm_started_at = ? where id = ?")
                .params(utc(Instant.now().minusSeconds(300)), Long.valueOf(stuck[0])).update();
        // The company still shows the payment as only authenticated: our confirm never reached it.
        when(gateway.lookup(stuck[1])).thenReturn(Payment.of(State.PENDING, "IN_PROGRESS"));
        when(gateway.confirm("pk_1", stuck[1], 1900)).thenReturn(done(1900));
        orderService.settleUnknown();
        verify(gateway, times(2)).confirm("pk_1", stuck[1], 1900);
        assertThat(orderStatus(stuck)).isEqualTo("paid");
        assertThat(entitlements(buyer)).isEqualTo(1);
    }

    @Test
    void anAdminWorksThroughOrdersRefundsAndClosesAccounts() throws Exception {
        Session admin = signUp("admin@example.com");
        String[] order = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(done(1900));
        confirm(buyer, order, "pk_1").andExpect(status().isOk());
        String base = "/api/v1/admin/orders";
        expectError(get(base, buyer.access()), 403, "PERMISSION_DENIED");
        get(base + "?status=paid", admin.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].user_id").value(buyer.userId()));
        get(base + "?status=review", admin.access()).andExpect(jsonPath("$.count").value(0));
        expectError(get(base + "?status=unknown", admin.access()), 400, "VALIDATION_ERROR");

        // A refund is believed only once the payment company itself reports it.
        String refund = base + "/" + order[0] + "/refund";
        when(gateway.cancel("pk_1", "고객 요청")).thenReturn(Payment.of(State.CANCELED, "CANCELED"));
        expectError(post(refund, admin.access(), q("{'reason':'고객 요청'}")), 503, "SERVICE_UNAVAILABLE");
        assertThat(entitlements(buyer)).isEqualTo(1);
        when(gateway.lookup(order[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_1", null, "CANCELED"));
        post(refund, admin.access(), q("{'reason':'고객 요청'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("refunded"));
        assertThat(entitlements(buyer)).isZero();
        expectError(post(refund, admin.access(), q("{'reason':'again'}")), 409, "INVALID_STATE");
        expectError(post(refund, buyer.access(), q("{'reason':'mine'}")), 403, "PERMISSION_DENIED");
        post(base + "/" + order[0] + "/reconcile", admin.access(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("refunded"));

        // Asking about an order nobody has started paying must not end it: the buyer may be paying right now.
        String[] fresh = pending(other, sheet);
        when(gateway.lookup(fresh[1])).thenReturn(Payment.of(State.FAILED, "NOT_FOUND_PAYMENT"));
        post(base + "/" + fresh[0] + "/reconcile", admin.access(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
        // A refund the company refuses because it was already refunded on its own screen still ends as refunded.
        when(gateway.confirm(eq("pk_9"), anyString(), anyLong())).thenReturn(done(1900));
        confirm(other, fresh, "pk_9").andExpect(status().isOk());
        when(gateway.cancel(eq("pk_9"), anyString())).thenReturn(Payment.of(State.FAILED, "ALREADY_CANCELED_PAYMENT"));
        when(gateway.lookup(fresh[1])).thenReturn(new Payment(State.CANCELED, 1900, "pk_9", null, "CANCELED"));
        post(base + "/" + fresh[0] + "/refund", admin.access(), q("{'reason':'이미 환불됨'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("refunded"));
        assertThat(entitlements(other)).isZero();

        // Closing an account ends its sessions at once.
        get("/api/v1/admin/users?email=Buyer@Example.com", admin.access())
                .andExpect(jsonPath("$.id").value(buyer.userId())).andExpect(jsonPath("$.is_active").value(true));
        patch("/api/v1/admin/users/" + buyer.userId(), admin.access(), q("{'is_active':false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.is_active").value(false));
        expectError(get(ORDERS, buyer.access()), 401, "AUTHENTICATION_REQUIRED");
        expectError(post("/api/v1/auth/token/refresh", null, q("{'refresh_token':'%s'}".formatted(buyer.refresh()))), 400, "VALIDATION_ERROR");
    }

    @Test
    void orderInputIsStrict() throws Exception {
        long hidden = activity("비공개", 500, false);
        // Each body breaks one rule only; everything else in it is as a real buyer sends it.
        String ok = ",'agreed_to_payment':true,'agreed_to_refund_notice':true";
        for (String body : List.of("{}", "{'activity_ids':[]" + ok + "}",
                "{'activity_ids':[%d,%d]%s}".formatted(sheet, sheet, ok),
                "{'activity_ids':['%d']%s}".formatted(sheet, ok),
                "{'activity_ids':[%d],'agreed_to_payment':false,'agreed_to_refund_notice':true}".formatted(sheet),
                "{'activity_ids':[%d],'agreed_to_refund_notice':true}".formatted(sheet),
                "{'activity_ids':[%d]%s,'payment_method_id':'pm_1'}".formatted(sheet, ok),
                "{'activity_ids':[%d]%s,'amount':1}".formatted(sheet, ok),
                "{'activity_ids':[%d]%s}".formatted(hidden, ok),
                "{'activity_ids':[999999]" + ok + "}",
                // A paid order is never made without the buyer's agreement to the refund notice.
                "{'activity_ids':[%d],'agreed_to_payment':true}".formatted(sheet),
                "{'activity_ids':[%d],'agreed_to_payment':true,'agreed_to_refund_notice':false}".formatted(sheet),
                "{'activity_ids':[%d],'agreed_to_payment':true,'agreed_to_refund_notice':'true'}".formatted(sheet))) {
            expectError(post(ORDERS, buyer.access(), q(body)), 400, "VALIDATION_ERROR");
        }
        assertThat(count("select count(*) from orders")).isZero();
        order(buyer, sheet).andExpect(status().isCreated());
        post(ORDERS, buyer.access(), q("{'activity_ids':[%d]%s,'payment_method_id':null}".formatted(sheet, ok)))
                .andExpect(status().isCreated());
        expectError(post(ORDERS, null, "{\"activity_ids\":[1],\"agreed_to_payment\":true}"), 401, "AUTHENTICATION_REQUIRED");
        expectError(get(ORDERS + "?sort=x", buyer.access()), 400, "VALIDATION_ERROR");
        expectError(get(ORDERS + "/999999", buyer.access()), 404, "NOT_FOUND");
    }

    // The webhook is open to anyone who knows an order's number, and each call costs a question to
    // the payment company with our key. Past a few a minute about one order it asks no more and
    // answers 503, so a real event is sent again later instead of being lost.
    @Test
    void theWebhookAsksAboutOneOrderOnlySoOftenAndOneBuyerConfirmsOnlySoOften() throws Exception {
        String[] order = pending(buyer, sheet);
        when(gateway.lookup(order[1])).thenReturn(Payment.of(State.PENDING, "IN_PROGRESS"));
        String event = q("{'data':{'orderId':'%s'}}".formatted(order[1]));
        for (int call = 0; call < 6; call++) {
            post(WEBHOOK, null, event).andExpect(status().isOk());
        }
        expectError(post(WEBHOOK, null, event), 503, "SERVICE_UNAVAILABLE");
        verify(gateway, times(6)).lookup(order[1]);
        // An order number we never issued is answered without a question and without a counter.
        long counters = count("select count(*) from auth_rate_limits");
        post(WEBHOOK, null, q("{'data':{'orderId':'ord_unknown'}}")).andExpect(status().isOk());
        assertThat(count("select count(*) from auth_rate_limits")).isEqualTo(counters);

        seedLimit("confirm", String.valueOf(buyer.userId()), 20);
        expectError(confirm(buyer, order, "pk_1"), 429, "RATE_LIMITED");
        verify(gateway, never()).confirm(anyString(), anyString(), anyLong());

        // An order that was closed before anyone tried to pay it has no payment to ask about, so old
        // order numbers cannot be used to make us ask. And one buyer makes only so many orders.
        String[] old = pending(other, sheet);
        jdbc.sql("update orders set status = 'cancelled' where id = ?").param(Long.valueOf(old[0])).update();
        post(WEBHOOK, null, q("{'data':{'orderId':'%s'}}".formatted(old[1]))).andExpect(status().isOk());
        verify(gateway, never()).lookup(old[1]);
        jdbc.sql("update auth_rate_limits set hits = 30 where key_digest = ?")
                .param(digests.of("rate", "order:" + other.userId())).update();
        expectError(order(other, sheet), 429, "RATE_LIMITED");
    }

    @Test
    void aPaidPdfOpensOnlyThroughTheBuyersTimedLink() throws Exception {
        String pdf = "/api/v1/activities/" + sheet + "/files/pdf";
        String download = "/api/v1/activities/" + sheet + "/download";
        expectError(get(pdf, null), 404, "NOT_FOUND");
        expectError(get(pdf, buyer.access()), 404, "NOT_FOUND");
        get("/api/v1/activities/" + sheet + "/files/image", null).andExpect(status().isOk());
        expectError(post(download, buyer.access(), null), 403, "PERMISSION_DENIED");
        expectError(post(download, null, null), 401, "AUTHENTICATION_REQUIRED");
        expectError(post("/api/v1/activities/999999/download", buyer.access(), null), 404, "NOT_FOUND");

        String[] order = pending(buyer, sheet);
        when(gateway.confirm(anyString(), anyString(), anyLong())).thenReturn(done(1900));
        confirm(buyer, order, "pk_1").andExpect(status().isOk());
        ResultActions issued = post(download, buyer.access(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.expires_at").isString());
        String link = ((String) read(issued, "$.download_url")).replace("http://localhost", "");
        assertThat(link).startsWith(pdf + "?token=");
        // What arrives is the buyer's own copy with their code on it, never the stored original.
        assertThat(get(link, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .startsWith("%PDF-".getBytes()).isNotEqualTo(samplePdf(1));
        expectError(post(download, other.access(), null), 403, "PERMISSION_DENIED");
        // Each delivery through a buyer's link leaves a record. A refused link leaves none, and neither
        // does a HEAD request, which asks about the file without receiving it.
        call("HEAD", link, null, null).andExpect(status().isOk());
        assertThat(count("select count(*) from pdf_downloads where user_id = ? and activity_id = ?", buyer.userId(), sheet))
                .isEqualTo(1);

        long another = activity("다른 유료", 500, true);
        String token = link.substring(link.indexOf("token=") + 6);
        for (String broken : List.of(pdf + "?token=" + token.substring(0, token.length() - 1) + (token.endsWith("0") ? "1" : "0"),
                pdf + "?token=garbage", "/api/v1/activities/" + another + "/files/pdf?token=" + token)) {
            expectError(get(broken, null), 404, "NOT_FOUND");
        }
        // What was paid for stays downloadable after the activity is taken off the catalogue.
        jdbc.sql("update activities set is_public = false where id = ?").param(sheet).update();
        String late = ((String) read(post(download, buyer.access(), null).andExpect(status().isOk()), "$.download_url"))
                .replace("http://localhost", "");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(late)
                .header("User-Agent", "Mozilla/5.0 (test) " + "x".repeat(300))).andExpect(status().isOk());
        // The record says where the file went, so a link passed on can be told from the buyer's own download.
        assertThat(jdbc.sql("select ip_address from pdf_downloads order by id desc limit 1").query(String.class).single())
                .isEqualTo("127.0.0.1");
        assertThat(jdbc.sql("select user_agent from pdf_downloads order by id desc limit 1").query(String.class).single())
                .startsWith("Mozilla/5.0 (test)").hasSize(255);
        get("/api/v1/purchases/activities", buyer.access()).andExpect(jsonPath("$.results[0].id").value(sheet));

        long free = activity("무료", 0, true);
        get("/api/v1/activities/" + free + "/files/pdf", null).andExpect(status().isOk());
        post("/api/v1/activities/" + free + "/download", other.access(), null).andExpect(status().isOk());
    }
}
