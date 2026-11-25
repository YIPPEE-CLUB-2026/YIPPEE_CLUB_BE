package club.yippee.api.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An error the client is meant to see, in the spec's shape: code, message, details. */
public class ApiException extends RuntimeException {

    public static final String CHECK_INPUT = "요청값을 확인해 주세요.";

    public final int status;
    public final String code;
    public final Map<String, List<String>> details;

    public ApiException(int status, String code, String message, Map<String, List<String>> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    /** 400 with a message written for the user. */
    public static ApiException invalid(String message) {
        return new ApiException(400, "VALIDATION_ERROR", message);
    }

    /** 400 naming the fields at fault. Submitted values are never echoed back. */
    public static ApiException fields(Iterable<String> names) {
        Map<String, List<String>> details = new LinkedHashMap<>();
        names.forEach(name -> details.put(name, List.of(CHECK_INPUT)));
        return new ApiException(400, "VALIDATION_ERROR", CHECK_INPUT, details);
    }

    /** 400 naming one field and saying why, in words written by this server, never the submitted value. */
    public static ApiException reason(String name, String why) {
        return new ApiException(400, "VALIDATION_ERROR", CHECK_INPUT, Map.of(name, List.of(why)));
    }

    public static ApiException field(String name) {
        return fields(List.of(name));
    }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "요청을 처리할 수 없습니다.");
    }
}
