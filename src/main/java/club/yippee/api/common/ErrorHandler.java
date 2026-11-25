package club.yippee.api.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error leaves in one shape: code, message, details, request_id. */
@RestControllerAdvice
public class ErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    private static final Map<Integer, String> CODES = Map.of(
            400, "VALIDATION_ERROR", 401, "AUTHENTICATION_REQUIRED", 403, "PERMISSION_DENIED", 413, "VALIDATION_ERROR",
            404, "NOT_FOUND", 409, "INVALID_STATE", 429, "RATE_LIMITED",
            500, "INTERNAL_SERVER_ERROR", 503, "SERVICE_UNAVAILABLE");

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException error) {
        return body(error.status, error.code, error.getMessage(), error.details, requestId());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException error) {
        return plain(400);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception error, HttpServletRequest request) {
        // 404, 405 and the like come from Spring itself and carry their own status.
        if (error instanceof ErrorResponse known && known.getStatusCode().value() < 500) {
            return plain(known.getStatusCode().value());
        }
        String requestId = requestId();
        // The cause stays in the server log, findable by the id the client was given.
        log.error("unhandled {} {} {}", requestId, request.getMethod(), request.getRequestURI(), error);
        return body(500, CODES.get(500), message(500), Map.of(), requestId);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException error) throws IOException {
        write(response, 401);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException error) throws IOException {
        write(response, 403);
    }

    private static ResponseEntity<Map<String, Object>> plain(int status) {
        return body(status, CODES.getOrDefault(status, "INVALID_STATE"), message(status), Map.of(), requestId());
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message,
                                                            Map<String, ?> details, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("details", details);
        body.put("request_id", requestId);
        return ResponseEntity.status(status).body(body);
    }

    private static String message(int status) {
        return status == 400 ? ApiException.CHECK_INPUT
                : status == 500 ? "서버 내부 오류가 발생했습니다." : "요청을 처리할 수 없습니다.";
    }

    // The id the request was given on arrival, so the error body, the header and the log all agree.
    private static String requestId() {
        String current = org.slf4j.MDC.get(RequestLogFilter.KEY);
        return current != null ? current : "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    // The security filters answer before any controller runs, so they write the same shape by hand.
    // Every part is a fixed string or a hex id: nothing needs escaping.
    static void write(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + CODES.get(status) + "\",\"message\":\"" + message(status)
                + "\",\"details\":{},\"request_id\":\"" + requestId() + "\"}");
    }
}
