package club.yippee.api.common;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Fixed-window counters kept in the database, so every server instance shares them. */
@Component
public class RateLimiter {

    private final JdbcClient jdbc;
    private final Digests digests;
    private final AppProperties.Auth auth;
    private final TransactionTemplate ownTransaction;

    RateLimiter(JdbcClient jdbc, Digests digests, AppProperties properties, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.digests = digests;
        this.auth = properties.auth();
        // A hit must stay counted even when the request it belongs to fails and rolls back.
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Counts one request from the caller's address. Behind a proxy see FORWARD_HEADERS_STRATEGY. */
    public void address(HttpServletRequest request, String scope) {
        consume(scope + ":ip", request.getRemoteAddr(), auth.ipLimit(), auth.rateWindowSeconds());
    }

    /** Counts one request about this email (or other identity). */
    public void identity(String scope, String identity) {
        consume(scope + ":email", identity, auth.emailLimit(), auth.rateWindowSeconds());
    }

    /**
     * Counts one attempt on this identity from the caller's address. Used for login: wrong guesses
     * made by someone else, somewhere else, must not use up the owner's own allowance and lock
     * the owner out. The price is that guesses from many addresses are not capped per account.
     */
    public void identityFrom(HttpServletRequest request, String scope, String identity) {
        consume(scope + ":email", request.getRemoteAddr() + "|" + identity, auth.emailLimit(), auth.rateWindowSeconds());
    }

    /** Counts one hit, or raises 429 when the window is full. */
    public void consume(String scope, String identity, int limit, int windowSeconds) {
        String key = digests.of("rate", scope + ":" + identity);
        boolean allowed;
        try {
            allowed = count(key, limit, windowSeconds);
        } catch (PessimisticLockingFailureException deadlock) {
            // The database ended a deadlock by failing this side. The other side is through, so once more.
            allowed = count(key, limit, windowSeconds);
        }
        if (!allowed) {
            throw new ApiException(429, "RATE_LIMITED", "잠시 후 다시 시도해 주세요.");
        }
    }

    private boolean count(String key, int limit, int windowSeconds) {
        long now = Instant.now().getEpochSecond();
        return Boolean.TRUE.equals(ownTransaction.execute(status -> {
            // One statement that creates the row or takes the existing one exclusively. On MySQL,
            // locking a missing row first can deadlock two newcomers, and a plain INSERT that hits a
            // duplicate keeps only a shared lock, which two holders then cannot both upgrade.
            jdbc.sql("insert into auth_rate_limits (key_digest, window_start, hits) values (?, ?, 0)"
                    + " on duplicate key update hits = hits").params(key, now).update();
            Map<String, Object> row = jdbc.sql(
                    "select window_start, hits from auth_rate_limits where key_digest = ? for update")
                    .param(key).query().singleRow();
            long start = ((Number) row.get("window_start")).longValue();
            int hits = ((Number) row.get("hits")).intValue();
            if (now - start >= windowSeconds) {
                start = now;
                hits = 0;
            }
            if (hits >= limit) {
                return false;
            }
            jdbc.sql("update auth_rate_limits set window_start = ?, hits = ? where key_digest = ?")
                    .params(start, hits + 1, key).update();
            return true;
        }));
    }
}
