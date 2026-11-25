package club.yippee.api.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id and writes one line when it ends: method, path, status, time, id.
 * The id is returned in the X-Request-Id header and in any error body, so a user's report can
 * be matched to the server's log. It runs before the security filters, so refused requests are
 * logged too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    static final String KEY = "request_id";
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Always our own id: one supplied by the client could be forged to confuse the log.
        String id = "req_" + UUID.randomUUID().toString().replace("-", "");
        MDC.put(KEY, id);
        response.setHeader("X-Request-Id", id);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // The path only: a download link carries its token in the query string.
            String path = request.getRequestURI();
            if (!path.startsWith("/api/v1/health")) {
                log.info("{} {} {} {}ms {}", request.getMethod(), path, response.getStatus(),
                        (System.nanoTime() - started) / 1_000_000, id);
            }
            MDC.remove(KEY);
        }
    }
}
