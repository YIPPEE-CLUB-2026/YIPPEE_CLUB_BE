package club.yippee.api.account;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.Fields;

/** Looking a customer up by email and closing or reopening an account. Admin accounts only. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserAdminController {

    private final UserRepository users;
    private final RefreshSessionRepository sessions;

    UserAdminController(UserRepository users, RefreshSessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, Object> find(@RequestParam String email) {
        return view(users.findByEmail(email.strip().toLowerCase(java.util.Locale.ROOT)).orElseThrow(ApiException::notFound));
    }

    /** {"is_active": false} closes the account at once: its tokens stop working on the next request. */
    @PatchMapping("/{id:[0-9]{1,18}}")
    @Transactional
    public Map<String, Object> setActive(@PathVariable Long id, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "is_active");
        if (!(fields.raw("is_active") instanceof Boolean active)) {
            throw ApiException.field("is_active");
        }
        User user = users.lockById(id).orElseThrow(ApiException::notFound);
        if (user.withdrawnAt != null) {
            throw new ApiException(409, "INVALID_STATE", "탈퇴한 계정입니다.");
        }
        user.active = active;
        if (!active) {
            sessions.revokeAll(user.id, java.time.Instant.now());
        }
        return view(user);
    }

    private static Map<String, Object> view(User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.id);
        body.put("email", user.email);
        body.put("name", user.name);
        body.put("nickname", user.nickname);
        body.put("is_active", user.active);
        body.put("created_at", user.createdAt.toString());
        body.put("last_login_at", user.lastLoginAt == null ? null : user.lastLoginAt.toString());
        return body;
    }
}
