package club.yippee.api.catalog;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.common.ApiException;

/** Looking up the code printed on a member's copy of a sheet. Admin accounts only. */
@RestController
@RequestMapping("/api/v1/admin/worksheet-issuances")
public class IssuanceAdminController {

    private final Issuances issuances;

    IssuanceAdminController(Issuances issuances) {
        this.issuances = issuances;
    }

    /** ?code= finds one copy by its whole code; ?user_id= and ?activity_id= narrow the list. Newest first. */
    @GetMapping
    Map<String, Object> list(@RequestParam Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>(params.keySet());
        faults.removeAll(Set.of("code", "user_id", "activity_id", "page", "page_size"));
        String code = params.get("code");
        if (code != null && (code.isBlank() || code.length() > 64)) {
            faults.add("code");
        }
        Integer userId = CatalogController.whole(params, "user_id", 1, Integer.MAX_VALUE, faults);
        Integer activityId = CatalogController.whole(params, "activity_id", 1, Integer.MAX_VALUE, faults);
        Integer page = CatalogController.whole(params, "page", 1, 1_000_000, faults);
        Integer size = CatalogController.whole(params, "page_size", 1, 100, faults);
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        return issuances.adminList(code, userId == null ? null : userId.longValue(),
                activityId == null ? null : activityId.longValue(), page == null ? 1 : page, size == null ? 20 : size);
    }

    /** One copy with the member's e-mail and each delivery of it. */
    @GetMapping("/{id:[0-9]{1,18}}")
    Map<String, Object> detail(@PathVariable Long id) {
        return issuances.adminDetail(id);
    }
}
