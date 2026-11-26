package club.yippee.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import club.yippee.api.common.Digests;

/** The sales chart. Every order here has a fixed time, so the Korean day, month and year edges are exact. */
class SalesApiTest extends ApiTestBase {

    private static final String SALES = "/api/v1/admin/sales";
    private Session admin;

    @BeforeEach
    void ordersAroundTheEdges() throws Exception {
        admin = signUp("admin@example.com");
        // Korea is nine hours ahead: its 25 November runs from the 24th 15:00 to the 25th 15:00 in UTC.
        order("paid", 1000, "2026-11-24T14:59:59", null);          // the 24th, 23:59:59 in Korea
        order("paid", 1900, "2026-11-24T15:00:00", null);          // the 25th, 00:00
        order("refunded", 2900, "2026-11-25T03:30:00", "2026-11-25T05:00:00");   // sold at 12, refunded at 14
        order("paid", 500, "2026-11-25T14:59:59", null);           // the 25th, 23:59:59
        order("paid", 0, "2026-11-25T01:00:00", null);             // a free order: no money, not counted
        order("pending", 7000, null, null);
        order("cancelled", 7000, null, null);
        order("payment_failed", 7000, null, null);
        // Money that came in the wrong amount and went back: it was never a sale, so it is no refund either.
        order("refunded", 800, null, "2026-11-25T04:00:00");
        order("refunded", 300, "2026-10-31T15:00:00", "2026-11-30T15:00:00");    // sold 1 November, refunded 1 December
        order("paid", 100, "2025-12-31T15:00:00", null);           // 1 January 2026
    }

    private void order(String status, int amount, String paidAt, String refundedAt) {
        jdbc.sql("insert into orders (user_id, status, amount, pg_order_id, paid_at, refunded_at, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?)")
                .params(admin.userId(), status, amount, "ord_" + Digests.randomHex(8),
                        paidAt == null ? null : LocalDateTime.parse(paidAt),
                        refundedAt == null ? null : LocalDateTime.parse(refundedAt), LocalDateTime.parse("2025-01-01T00:00:00"))
                .update();
    }

    @Test
    void aDayIsCountedByTheHourInKoreanTime() throws Exception {
        get(SALES + "?unit=day&date=2026-11-25", admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("day"))
                .andExpect(jsonPath("$.date").value("2026-11-25"))
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.paid_amount").value(5300))
                .andExpect(jsonPath("$.refund_amount").value(2900))
                .andExpect(jsonPath("$.net_amount").value(2400))
                .andExpect(jsonPath("$.paid_count").value(3))
                .andExpect(jsonPath("$.refund_count").value(1))
                .andExpect(jsonPath("$.buckets.length()").value(24))
                .andExpect(jsonPath("$.buckets[0].key").value(0))
                .andExpect(jsonPath("$.buckets[0].paid_amount").value(1900))
                .andExpect(jsonPath("$.buckets[12].paid_amount").value(2900))
                .andExpect(jsonPath("$.buckets[12].refund_amount").value(0))
                .andExpect(jsonPath("$.buckets[14].refund_amount").value(2900))
                .andExpect(jsonPath("$.buckets[14].net_amount").value(-2900))
                .andExpect(jsonPath("$.buckets[23].key").value(23))
                .andExpect(jsonPath("$.buckets[23].paid_amount").value(500));
    }

    @Test
    void aMonthIsCountedByTheDayAndARefundFallsOnTheDayItWasMade() throws Exception {
        get(SALES + "?unit=month&date=2026-11", admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_amount").value(6600))
                .andExpect(jsonPath("$.refund_amount").value(2900))
                .andExpect(jsonPath("$.net_amount").value(3700))
                .andExpect(jsonPath("$.buckets.length()").value(30))
                .andExpect(jsonPath("$.buckets[0].key").value(1))
                .andExpect(jsonPath("$.buckets[0].paid_amount").value(300))
                .andExpect(jsonPath("$.buckets[23].paid_amount").value(1000))
                .andExpect(jsonPath("$.buckets[24].paid_amount").value(5300));
        // The sheet sold in November and refunded in December: December is in the red by that much.
        get(SALES + "?unit=month&date=2026-12", admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_amount").value(0))
                .andExpect(jsonPath("$.refund_amount").value(300))
                .andExpect(jsonPath("$.net_amount").value(-300))
                .andExpect(jsonPath("$.buckets.length()").value(31))
                .andExpect(jsonPath("$.buckets[0].refund_amount").value(300));
    }

    @Test
    void aYearIsCountedByTheMonth() throws Exception {
        get(SALES + "?unit=year&date=2026", admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_amount").value(6700))
                .andExpect(jsonPath("$.refund_amount").value(3200))
                .andExpect(jsonPath("$.buckets.length()").value(12))
                .andExpect(jsonPath("$.buckets[0].key").value(1))
                .andExpect(jsonPath("$.buckets[0].paid_amount").value(100))
                .andExpect(jsonPath("$.buckets[10].paid_amount").value(6600))
                .andExpect(jsonPath("$.buckets[11].refund_amount").value(300));
        get(SALES + "?unit=year&date=2025", admin.access())
                .andExpect(jsonPath("$.paid_amount").value(0)).andExpect(jsonPath("$.paid_count").value(0));
    }

    @Test
    void onlyAnAdminAsksAndOnlyInTheKnownForm() throws Exception {
        expectError(get(SALES + "?unit=day&date=2026-11-25", null), 401, "AUTHENTICATION_REQUIRED");
        expectError(get(SALES + "?unit=day&date=2026-11-25", signUp("member@example.com").access()), 403, "PERMISSION_DENIED");
        for (String query : new String[]{"", "?unit=day", "?date=2026-11-25", "?unit=week&date=2026-11-25",
                "?unit=day&date=2026-11", "?unit=day&date=2026-02-30", "?unit=month&date=2026-13",
                "?unit=month&date=2026-11-25", "?unit=year&date=26", "?unit=year&date=2026&extra=1"}) {
            expectError(get(SALES + query, admin.access()), 400, "VALIDATION_ERROR");
        }
    }
}
