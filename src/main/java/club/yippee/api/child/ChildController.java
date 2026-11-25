package club.yippee.api.child;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.Fields;

@RestController
public class ChildController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ChildService children;

    ChildController(ChildService children) {
        this.children = children;
    }

    @GetMapping("/api/v1/children")
    Map<String, Object> list(@AuthenticationPrincipal Long userId) {
        return Map.of("results", children.list(userId));
    }

    @PostMapping("/api/v1/children")
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Long userId,
                                               @RequestBody(required = false) Object body) {
        return ResponseEntity.status(201).body(children.create(userId, read(body, true)));
    }

    @GetMapping("/api/v1/children/{id:[0-9]+}")
    Map<String, Object> detail(@AuthenticationPrincipal Long userId, @PathVariable String id) {
        return children.get(userId, number(id));
    }

    @PatchMapping("/api/v1/children/{id:[0-9]+}")
    Map<String, Object> update(@AuthenticationPrincipal Long userId, @PathVariable String id,
                               @RequestBody(required = false) Object body) {
        return children.update(userId, number(id), read(body, false));
    }

    @DeleteMapping("/api/v1/children/{id:[0-9]+}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable String id) {
        children.delete(userId, number(id));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/users/me/active-child")
    Map<String, Object> select(@AuthenticationPrincipal Long userId, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "child_id");
        Long childId = fields.id("child_id");
        fields.check();
        return Map.of("active_child_id", children.select(userId, childId));
    }

    // An id too long to be one is an id that does not exist.
    private static Long number(String digits) {
        if (digits.length() > 18) {
            throw ApiException.notFound();
        }
        return Long.valueOf(digits);
    }

    /**
     * On create, name and birth date are required. On update every field is optional: left out
     * keeps the value, while null clears gender and interests and is an error for the other two.
     */
    private static ChildService.Changes read(Object body, boolean creating) {
        Fields fields = Fields.strict(body, "name", "birth_date", "gender", "interests");
        String name = creating || fields.has("name") ? fields.text("name", 100, false) : null;
        LocalDate birthDate = null;
        if (creating || fields.has("birth_date")) {
            String raw = fields.string("birth_date");
            birthDate = raw == null ? null : date(raw);
            if (raw != null && (birthDate == null || birthDate.isAfter(LocalDate.now(SEOUL)))) {
                fields.reject("birth_date");
            }
        }
        String gender = !fields.has("gender") ? (creating ? "" : null)
                : fields.isNull("gender") ? "" : fields.text("gender", 20, true);
        List<Long> interests = !fields.has("interests") ? (creating ? List.of() : null)
                : fields.isNull("interests") ? List.of() : ids(fields);
        fields.check();
        return new ChildService.Changes(name, birthDate, gender, interests);
    }

    private static LocalDate date(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    // A JSON list of distinct category ids, each a real integer.
    private static List<Long> ids(Fields fields) {
        List<Long> ids = new ArrayList<>();
        if (fields.raw("interests") instanceof List<?> items) {
            for (Object item : items) {
                Long id = Fields.asId(item);
                if (id == null) {
                    fields.reject("interests");
                    return null;
                }
                ids.add(id);
            }
            if (new HashSet<>(ids).size() == ids.size()) {
                return ids;
            }
        }
        fields.reject("interests");
        return null;
    }
}
