package club.yippee.api.order;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.common.ApiException;

/**
 * The sales chart for the people running the service. Admin accounts only.
 *
 * Money in is counted at the moment an order was paid, money out at the moment it was refunded, each
 * in Korean time. So a refund never rewrites the day of the sale: it shows on its own day.
 * Orders that were never paid (pending, cancelled, failed) have no payment time and are not counted,
 * and neither is money that arrived in a wrong amount and was sent back (it was never a sale).
 */
@RestController
public class SalesController {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbc;

    SalesController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** ?unit=day&date=2026-11-25 by the hour, ?unit=month&date=2026-11 by the day, ?unit=year&date=2026 by the month. */
    @GetMapping("/api/v1/admin/sales")
    Map<String, Object> sales(@RequestParam Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>(params.keySet());
        faults.removeAll(Set.of("unit", "date"));
        String unit = params.get("unit");
        String date = params.getOrDefault("date", "");
        LocalDate first = null;
        try {
            if ("day".equals(unit) && date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                first = LocalDate.parse(date);
            } else if ("month".equals(unit) && date.matches("\\d{4}-\\d{2}")) {
                first = LocalDate.parse(date + "-01");
            } else if ("year".equals(unit) && date.matches("\\d{4}")) {
                first = LocalDate.parse(date + "-01-01");
            }
        } catch (DateTimeException noSuchDay) {
            // Left null: reported below.
        }
        if (first == null) {
            faults.add(Set.of("day", "month", "year").contains(String.valueOf(unit)) ? "date" : "unit");
        }
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        LocalDate end = switch (unit) {
            case "day" -> first.plusDays(1);
            case "month" -> first.plusMonths(1);
            default -> first.plusYears(1);
        };
        // Which bar of the chart a moment belongs to, and how many bars there are.
        ToIntFunction<LocalDateTime> bar = switch (unit) {
            case "day" -> LocalDateTime::getHour;
            case "month" -> moment -> moment.getDayOfMonth() - 1;
            default -> moment -> moment.getMonthValue() - 1;
        };
        int bars = switch (unit) {
            case "day" -> 24;
            case "month" -> first.lengthOfMonth();
            default -> 12;
        };
        long[] paid = new long[bars];
        long[] refunded = new long[bars];
        int paidCount = sum("paid_at", first, end, bar, paid);
        int refundCount = sum("refunded_at", first, end, bar, refunded);

        List<Map<String, Object>> buckets = new ArrayList<>();
        long paidTotal = 0;
        long refundTotal = 0;
        for (int index = 0; index < bars; index++) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            // The hour (0 to 23), the day of the month or the month, as the calendar numbers them.
            bucket.put("key", "day".equals(unit) ? index : index + 1);
            bucket.put("paid_amount", paid[index]);
            bucket.put("refund_amount", refunded[index]);
            bucket.put("net_amount", paid[index] - refunded[index]);
            buckets.add(bucket);
            paidTotal += paid[index];
            refundTotal += refunded[index];
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unit", unit);
        body.put("date", date);
        body.put("timezone", KOREA.getId());
        body.put("paid_amount", paidTotal);
        body.put("refund_amount", refundTotal);
        body.put("net_amount", paidTotal - refundTotal);
        body.put("paid_count", paidCount);
        body.put("refund_count", refundCount);
        body.put("buckets", buckets);
        return body;
    }

    // Adds each order's amount to the bar its moment falls in, and returns how many orders there were.
    // The column is one of two fixed names, never anything from the request.
    // shortcut: the period's orders are read and added up here, which is fine for thousands of orders
    // a year. Sum in the database (by hour, day or month in Korean time) when that stops being true.
    private int sum(String column, LocalDate first, LocalDate end, ToIntFunction<LocalDateTime> bar, long[] into) {
        List<Object[]> rows = jdbc.sql("select " + column + " as moment, amount from orders where " + column + " >= ? and "
                        + column + " < ? and paid_at is not null and amount > 0")
                .params(utc(first), utc(end))
                .query((row, index) -> new Object[]{row.getObject("moment", LocalDateTime.class), row.getLong("amount")})
                .list();
        for (Object[] row : rows) {
            // Stored times are UTC wall-clock values.
            LocalDateTime korean = ((LocalDateTime) row[0]).atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).toLocalDateTime();
            into[bar.applyAsInt(korean)] += (Long) row[1];
        }
        return rows.size();
    }

    // Midnight of that Korean day, as the UTC wall-clock value the database holds.
    private static LocalDateTime utc(LocalDate koreanDay) {
        return LocalDateTime.ofInstant(koreanDay.atStartOfDay(KOREA).toInstant(), ZoneOffset.UTC);
    }
}
