package club.yippee.api.child;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import club.yippee.api.common.ApiException;

/** A user's children. Every method takes the owner from the token, never from the request. */
@Service
public class ChildService {

    /** Validated input. A null part means "leave as it is". */
    record Changes(String name, LocalDate birthDate, String gender, List<Long> interests) {
    }

    private final ChildRepository children;
    private final JdbcClient jdbc;

    ChildService(ChildRepository children, JdbcClient jdbc) {
        this.children = children;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId) {
        return children.findByUserIdOrderById(userId).stream().map(ChildService::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long userId, Long id) {
        return view(children.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound));
    }

    @Transactional
    public Map<String, Object> create(Long userId, Changes changes) {
        // Under the owner's lock: a request that was waiting while the member left must not
        // write a child's name into the emptied account.
        lockOwner(userId);
        return view(children.save(apply(new Child(userId), changes)));
    }

    @Transactional
    public Map<String, Object> update(Long userId, Long id, Changes changes) {
        return view(apply(children.lockOwned(id, userId).orElseThrow(ApiException::notFound), changes));
    }

    /** Deleting the chosen child clears the choice (the foreign key does it). Nothing else of the user goes. */
    @Transactional
    public void delete(Long userId, Long id) {
        Child child = children.lockOwned(id, userId).orElseThrow(ApiException::notFound);
        lockOwner(userId);
        children.delete(child);
    }

    /** Remembers which of the user's own children is the current one. */
    @Transactional
    public Long select(Long userId, Long childId) {
        // Same lock order as delete: child, then owner.
        if (children.lockOwned(childId, userId).isEmpty()) {
            // 400, not 404, and one answer for "missing" and "someone else's".
            throw ApiException.field("child_id");
        }
        lockOwner(userId);
        jdbc.sql("update users set active_child_id = ? where id = ?").params(childId, userId).update();
        return childId;
    }

    private void lockOwner(Long userId) {
        jdbc.sql("select id from users where id = ? and is_active = true for update").param(userId).query(Long.class)
                .optional().orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "요청을 처리할 수 없습니다."));
    }

    private Child apply(Child child, Changes changes) {
        if (changes.name() != null) {
            child.name = changes.name();
        }
        if (changes.birthDate() != null) {
            child.birthDate = changes.birthDate();
        }
        if (changes.gender() != null) {
            child.gender = changes.gender();
        }
        if (changes.interests() != null) {
            List<Long> wanted = changes.interests();
            if (!wanted.isEmpty() && jdbc.sql("select count(*) from categories where id in (:ids)")
                    .param("ids", wanted).query(Long.class).single() != wanted.size()) {
                throw ApiException.field("interests");
            }
            child.interests.clear();
            child.interests.addAll(wanted);
        }
        return child;
    }

    private static Map<String, Object> view(Child child) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", child.id);
        body.put("name", child.name);
        body.put("birth_date", child.birthDate.toString());
        body.put("gender", child.gender);
        body.put("interests", new TreeSet<>(child.interests));
        return body;
    }
}
