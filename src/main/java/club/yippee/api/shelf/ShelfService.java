package club.yippee.api.shelf;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import club.yippee.api.catalog.Activities;
import club.yippee.api.common.ApiException;

/**
 * Favorites and the cart are the same thing underneath: a user's list of activities, one row
 * per pair. One implementation serves both tables.
 */
@Service
public class ShelfService {

    public enum Shelf {
        FAVORITES("favorites", "이미 즐겨찾기에 등록된 활동지입니다."),
        CART("cart_items", "이미 장바구니에 등록된 활동지입니다.");

        // Fixed names from this enum, never from a request, so they are safe to put into SQL.
        private final String table;
        private final String duplicateMessage;

        Shelf(String table, String duplicateMessage) {
            this.table = table;
            this.duplicateMessage = duplicateMessage;
        }

        public ApiException duplicate() {
            return new ApiException(409, "INVALID_STATE", duplicateMessage);
        }
    }

    private final JdbcClient jdbc;
    private final Activities activities;

    ShelfService(JdbcClient jdbc, Activities activities) {
        this.jdbc = jdbc;
        this.activities = activities;
    }

    /** Adds a public activity once. The row lock keeps an unpublish from slipping in between. */
    @Transactional
    public void add(Shelf shelf, Long userId, Long activityId) {
        if (activities.lockPublic(activityId).isEmpty()) {
            // 400 by contract; "missing" and "not public" share one answer.
            throw ApiException.field("activity_id");
        }
        // Only what is owned for good is kept out of the cart: a 7-day pass can still be bought for good.
        if (shelf == Shelf.CART && jdbc.sql("select count(*) from purchase_entitlements"
                        + " where user_id = ? and activity_id = ? and status = 'active' and expires_at is null")
                .params(userId, activityId).query(Long.class).single() > 0) {
            throw new ApiException(409, "ALREADY_PURCHASED", "이미 구매한 활동지입니다.");
        }
        if (jdbc.sql("select count(*) from " + shelf.table + " where user_id = ? and activity_id = ?")
                .params(userId, activityId).query(Long.class).single() > 0) {
            throw shelf.duplicate();
        }
        jdbc.sql("insert into " + shelf.table + " (user_id, activity_id) values (?, ?)")
                .params(userId, activityId).update();
    }

    /** Removes the user's own row, published or not. False when there was none. */
    public boolean remove(Shelf shelf, Long userId, Long activityId) {
        return jdbc.sql("delete from " + shelf.table + " where user_id = ? and activity_id = ?")
                .params(userId, activityId).update() > 0;
    }

    /** The user's public activities on this shelf, newest first. */
    public List<Long> activityIds(Shelf shelf, Long userId, int limit, long offset) {
        return jdbc.sql("select s.activity_id from " + shelf.table + " s join activities a on a.id = s.activity_id"
                        + " where s.user_id = ? and a.is_public = true order by s.id desc limit ? offset ?")
                .params(userId, limit, offset).query(Long.class).list();
    }
}
