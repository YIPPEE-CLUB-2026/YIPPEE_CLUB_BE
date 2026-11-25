package club.yippee.api.shelf;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import club.yippee.api.catalog.Activities;
import club.yippee.api.catalog.CatalogController;
import club.yippee.api.common.ApiException;
import club.yippee.api.common.Fields;
import club.yippee.api.shelf.ShelfService.Shelf;

/** Favorites and the cart. The user is always the one in the token. */
@RestController
public class ShelfController {

    private final ShelfService shelves;
    private final Activities activities;

    ShelfController(ShelfService shelves, Activities activities) {
        this.shelves = shelves;
        this.activities = activities;
    }

    @GetMapping("/api/v1/favorites")
    Map<String, Object> favorites(@AuthenticationPrincipal Long userId, @RequestParam Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>(params.keySet());
        faults.removeAll(Set.of("page", "page_size"));
        Integer page = CatalogController.whole(params, "page", 1, Integer.MAX_VALUE, faults);
        Integer size = CatalogController.whole(params, "page_size", 1, 100, faults);
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        int pageSize = size == null ? 20 : size;
        long offset = (long) ((page == null ? 1 : page) - 1) * pageSize;
        // A page past the end is an empty page, not an error.
        return Map.of("results", activities.viewsInOrder(shelves.activityIds(Shelf.FAVORITES, userId, pageSize, offset)));
    }

    @GetMapping("/api/v1/cart")
    Map<String, Object> cart(@AuthenticationPrincipal Long userId, @RequestParam Map<String, String> params) {
        if (!params.isEmpty()) {
            // The contract defines no filters or paging for the cart.
            throw ApiException.fields(params.keySet());
        }
        List<Map<String, Object>> items = activities.viewsInOrder(
                shelves.activityIds(Shelf.CART, userId, Integer.MAX_VALUE, 0));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        // Summed from the stored prices; a client never supplies an amount.
        body.put("total_amount", items.stream().mapToLong(item -> ((Number) item.get("price")).longValue()).sum());
        return body;
    }

    @PostMapping("/api/v1/favorites")
    ResponseEntity<Map<String, Object>> addFavorite(@AuthenticationPrincipal Long userId,
                                                    @RequestBody(required = false) Object body) {
        return add(Shelf.FAVORITES, userId, body);
    }

    @PostMapping("/api/v1/cart/items")
    ResponseEntity<Map<String, Object>> addToCart(@AuthenticationPrincipal Long userId,
                                                  @RequestBody(required = false) Object body) {
        return add(Shelf.CART, userId, body);
    }

    @DeleteMapping("/api/v1/favorites/{activityId:[0-9]+}")
    ResponseEntity<Void> removeFavorite(@AuthenticationPrincipal Long userId, @PathVariable String activityId) {
        return remove(Shelf.FAVORITES, userId, activityId);
    }

    @DeleteMapping("/api/v1/cart/items/{activityId:[0-9]+}")
    ResponseEntity<Void> removeFromCart(@AuthenticationPrincipal Long userId, @PathVariable String activityId) {
        return remove(Shelf.CART, userId, activityId);
    }

    private ResponseEntity<Map<String, Object>> add(Shelf shelf, Long userId, Object body) {
        Fields fields = Fields.strict(body, "activity_id");
        Long activityId = fields.id("activity_id");
        fields.check();
        try {
            shelves.add(shelf, userId, activityId);
        } catch (DataIntegrityViolationException sameRowJustAdded) {
            // Two identical requests at once: the unique index let one through.
            throw shelf.duplicate();
        }
        return ResponseEntity.status(201).body(Map.of("activity_id", activityId, "message", "처리되었습니다."));
    }

    private ResponseEntity<Void> remove(Shelf shelf, Long userId, String digits) {
        Long activityId = digits.length() <= 18 ? Fields.asId(Long.valueOf(digits)) : null;
        if (activityId == null) {
            throw ApiException.field("activity_id");
        }
        if (!shelves.remove(shelf, userId, activityId)) {
            throw ApiException.notFound();
        }
        return ResponseEntity.noContent().build();
    }
}
