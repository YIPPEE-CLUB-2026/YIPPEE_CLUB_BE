package club.yippee.api.common;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a JSON request body by hand so that types are never coerced: "1", 1.0 and true are not
 * the integer 1, a number is not a string, and a missing field is not a null one.
 * Faults are collected by field name and raised together as one 400.
 */
public final class Fields {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");

    private final Map<?, ?> map;
    private final Set<String> faults = new LinkedHashSet<>();

    private Fields(Object body) {
        if (!(body instanceof Map<?, ?> object)) {
            throw ApiException.field("non_field_errors");
        }
        this.map = object;
    }

    /** Only the listed names may appear; any other is a fault. */
    public static Fields strict(Object body, String... allowed) {
        Fields fields = new Fields(body);
        Set<String> names = Set.of(allowed);
        for (Object key : fields.map.keySet()) {
            if (!names.contains(key)) {
                fields.faults.add(String.valueOf(key));
            }
        }
        return fields;
    }

    /** Unknown names are ignored. */
    public static Fields lenient(Object body) {
        return new Fields(body);
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public boolean isNull(String name) {
        return map.containsKey(name) && map.get(name) == null;
    }

    public Object raw(String name) {
        return map.get(name);
    }

    /** A JSON string exactly as sent. Missing, null or any other type is a fault. */
    public String string(String name) {
        if (map.get(name) instanceof String value) {
            return value;
        }
        faults.add(name);
        return null;
    }

    /** A string with outer whitespace removed, at most max characters; empty only when allowed. */
    public String text(String name, int max, boolean emptyAllowed) {
        String value = string(name);
        if (value == null) {
            return null;
        }
        value = value.strip();
        if (value.length() > max || (value.isEmpty() && !emptyAllowed)) {
            faults.add(name);
            return null;
        }
        return value;
    }

    /** An email address, trimmed and lower-cased. */
    public String email(String name) {
        String value = text(name, 254, false);
        if (value != null && normalEmail(value) == null) {
            faults.add(name);
            return null;
        }
        return normalEmail(value);
    }

    /** The address trimmed and lower-cased, or null when it is not one we accept. */
    public static String normalEmail(String value) {
        String trimmed = value == null ? "" : value.strip();
        return trimmed.isEmpty() || trimmed.length() > 254 || !EMAIL.matcher(trimmed).matches()
                ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** A JSON integer from 1 to the largest id. */
    public Long id(String name) {
        Long value = asId(map.get(name));
        if (value == null) {
            faults.add(name);
        }
        return value;
    }

    /** The id held by a parsed JSON value, or null: only whole numbers that fit count. */
    public static Long asId(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            return number >= 1 ? number : null;
        }
        return null;
    }

    /** A JSON integer within bounds; anything else is a fault. */
    public Integer whole(String name, int min, int max) {
        if (map.get(name) instanceof Integer value && value >= min && value <= max) {
            return value;
        }
        faults.add(name);
        return null;
    }

    public void reject(String name) {
        faults.add(name);
    }

    /** Raises one 400 naming every field at fault. */
    public void check() {
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
    }
}
