package club.yippee.api.common;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of a request body before anything parses it. Every JSON request here is a
 * handful of short fields. File uploads are multipart and have their own limits.
 */
@Component
public class BodyLimitFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            ErrorHandler.write(response, 413);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                return new Capped(super.getInputStream());
            }
        }, response);
    }

    // A body sent without a length (chunked) is cut off at the same limit while it is read.
    private static final class Capped extends ServletInputStream {

        private final ServletInputStream source;
        private long count;

        Capped(ServletInputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            if (value >= 0 && ++count > MAX_BYTES) {
                throw new IOException("request body too large");
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = source.read(buffer, offset, length);
            if (read > 0 && (count += read) > MAX_BYTES) {
                throw new IOException("request body too large");
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return source.isFinished();
        }

        @Override
        public boolean isReady() {
            return source.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            source.setReadListener(listener);
        }
    }
}
