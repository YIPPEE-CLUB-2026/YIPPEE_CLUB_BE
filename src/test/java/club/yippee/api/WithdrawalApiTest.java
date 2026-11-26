package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import club.yippee.api.account.ProfileController;

/** A member leaving: what is destroyed at once, what the trade records keep, and for how long. */
class WithdrawalApiTest extends ApiTestBase {

    private static final String ME = "/api/v1/users/me";

    @Autowired
    ProfileController profiles;

    private long activity() {
        LocalDateTime now = utc(Instant.now());
        jdbc.sql("insert into activities (title, description, age_min_months, age_max_months, difficulty, duration_min,"
                        + " duration_max, price, image_path, pdf_path, is_public, created_at, updated_at)"
                        + " values ('활동지', '설명', 60, 65, 'easy', 5, 5, 1900, 'a/c.png', 'a/p.pdf', true, ?, ?)")
                .params(now, now).update();
        return count("select max(id) from activities");
    }

    private long order(Session who, String status, boolean confirmStarted) {
        LocalDateTime now = utc(Instant.now());
        jdbc.sql("insert into orders (user_id, status, amount, pg_order_id, confirm_started_at, paid_at, created_at)"
                        + " values (?, ?, 1900, ?, ?, ?, ?)")
                .params(who.userId(), status, "ord_" + System.nanoTime(), confirmStarted ? now : null,
                        status.equals("paid") ? now : null, now).update();
        return count("select max(id) from orders");
    }

    // Everything a member can leave behind: a child, a cart, a favourite, a provider link, a paid
    // order with its right, a download and a refund request that was decided.
    private Session memberWithHistory(String email) throws Exception {
        Session member = signUp(email);
        long sheet = activity();
        LocalDateTime now = utc(Instant.now());
        post("/api/v1/children", member.access(), q("{'name':'하늘','birth_date':'2021-03-01'}")).andExpect(status().isCreated());
        jdbc.sql("insert into cart_items (user_id, activity_id) values (?, ?)").params(member.userId(), sheet).update();
        jdbc.sql("insert into favorites (user_id, activity_id) values (?, ?)").params(member.userId(), sheet).update();
        jdbc.sql("insert into social_accounts (user_id, provider, provider_user_id, email_verified, created_at)"
                + " values (?, 'kakao', '77', true, ?)").params(member.userId(), now).update();
        long paid = order(member, "paid", true);
        jdbc.sql("insert into order_items (order_id, activity_id, title, price, plan) values (?, ?, '활동지', 1900, 'permanent')")
                .params(paid, sheet).update();
        jdbc.sql("insert into purchase_entitlements (user_id, activity_id, order_id, status, granted_at)"
                + " values (?, ?, ?, 'active', ?)").params(member.userId(), sheet, paid, now).update();
        jdbc.sql("insert into pdf_downloads (user_id, activity_id, downloaded_at, ip_address, user_agent)"
                + " values (?, ?, ?, '203.0.113.7', 'Mozilla')").params(member.userId(), sheet, now).update();
        jdbc.sql("insert into refund_requests (order_id, user_id, reason, status, created_at, decided_at)"
                + " values (?, ?, '사유', 'rejected', ?, ?)").params(paid, member.userId(), now, now).update();
        return member;
    }

    @Test
    void leavingDestroysWhatIsPersonalAndKeepsTheTradeRecordsApart() throws Exception {
        Session member = memberWithHistory("parent@example.com");
        long pending = order(member, "pending", false);

        delete(ME, member.access()).andExpect(status().isOk()).andExpect(jsonPath("$.message").isString());

        // Nothing opens the account any more.
        expectError(get(ME, member.access()), 401, "AUTHENTICATION_REQUIRED");
        expectError(post("/api/v1/auth/token/refresh", null, q("{'refresh_token':'%s'}".formatted(member.refresh()))),
                400, "VALIDATION_ERROR");
        expectError(post("/api/v1/auth/login", null, q("{'email':'parent@example.com','password':'%s'}".formatted(PASSWORD))),
                400, "VALIDATION_ERROR");

        // The member's row is emptied, not removed.
        var row = jdbc.sql("select email, name, nickname, phone, agreements, is_active, withdrawn_at, active_child_id"
                + " from users where id = ?").param(member.userId()).query().singleRow();
        assertThat(row.get("email")).isEqualTo("withdrawn-" + member.userId() + "@withdrawn.invalid");
        assertThat(row.get("name")).isEqualTo("");
        assertThat(row.get("agreements")).isEqualTo("{}");
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat(row.get("withdrawn_at")).isNotNull();
        assertThat(row.get("active_child_id")).isNull();
        for (String gone : new String[]{"children", "cart_items", "favorites", "social_accounts"}) {
            assertThat(count("select count(*) from " + gone + " where user_id = ?", member.userId())).as(gone).isZero();
        }
        // The trade records stay as they were; an order nobody had begun to pay is closed.
        assertThat(count("select count(*) from orders where user_id = ? and status = 'paid'", member.userId())).isEqualTo(1);
        assertThat(jdbc.sql("select status from orders where id = ?").param(pending).query(String.class).single())
                .isEqualTo("cancelled");
        for (String kept : new String[]{"refund_requests", "pdf_downloads", "purchase_entitlements"}) {
            assertThat(count("select count(*) from " + kept + " where user_id = ?", member.userId())).as(kept).isEqualTo(1);
        }
        // Who the buyer was is kept apart, for five years.
        assertThat(jdbc.sql("select email from withdrawn_members where user_id = ?").param(member.userId())
                .query(String.class).single()).isEqualTo("parent@example.com");
        LocalDateTime fiveYears = utc(Instant.now()).plusYears(5);
        assertThat(jdbc.sql("select purge_after from withdrawn_members").query(LocalDateTime.class).single())
                .isBetween(fiveYears.minusMinutes(5), fiveYears.plusMinutes(5));

        // The address and the provider identity are free again: a new, separate account.
        Session again = signUp("parent@example.com");
        assertThat(again.userId()).isNotEqualTo(member.userId());
        get("/api/v1/children", again.access()).andExpect(jsonPath("$.results.length()").value(0));

        // An admin cannot bring the emptied account back, and a search by the address finds the new one.
        Session admin = signUp("admin@example.com");
        expectError(patch("/api/v1/admin/users/" + member.userId(), admin.access(), q("{'is_active':true}")), 409, "INVALID_STATE");
        get("/api/v1/admin/users?email=parent@example.com", admin.access()).andExpect(jsonPath("$.id").value(again.userId()));
    }

    @Test
    void aMemberWhoNeverOrderedLeavesNothingBehind() throws Exception {
        Session member = signUp("nobuy@example.com");
        long sheet = activity();
        jdbc.sql("insert into pdf_downloads (user_id, activity_id, downloaded_at, ip_address, user_agent)"
                + " values (?, ?, ?, '203.0.113.7', 'Mozilla')").params(member.userId(), sheet, utc(Instant.now())).update();
        delete(ME, member.access()).andExpect(status().isOk());
        assertThat(count("select count(*) from withdrawn_members")).isZero();
        assertThat(count("select count(*) from pdf_downloads where ip_address is not null or user_agent is not null")).isZero();
        assertThat(count("select count(*) from users where email = 'nobuy@example.com'")).isZero();
        assertThat(count("select count(*) from users where last_login_at is not null")).isZero();

        // Neither does one whose only orders were free or never paid: no money moved, no name is kept.
        Session browser = signUp("browser@example.com");
        order(browser, "pending", false);
        jdbc.sql("update orders set amount = 0, status = 'paid' where id = ?").param(order(browser, "pending", false)).update();
        delete(ME, browser.access()).andExpect(status().isOk());
        assertThat(count("select count(*) from withdrawn_members")).isZero();
    }

    @Test
    void leavingWaitsForMoneyThatIsStillMoving() throws Exception {
        expectError(delete(ME, null), 401, "AUTHENTICATION_REQUIRED");

        Session paying = signUp("paying@example.com");
        order(paying, "pending", true);
        expectError(delete(ME, paying.access()), 409, "INVALID_STATE");

        Session reviewed = signUp("review@example.com");
        order(reviewed, "review", true);
        expectError(delete(ME, reviewed.access()), 409, "INVALID_STATE");

        Session asking = signUp("asking@example.com");
        long paid = order(asking, "paid", true);
        jdbc.sql("insert into refund_requests (order_id, user_id, reason, status, created_at) values (?, ?, '사유', 'requested', ?)")
                .params(paid, asking.userId(), utc(Instant.now())).update();
        expectError(delete(ME, asking.access()), 409, "INVALID_STATE");

        // An admin's address decides who is an admin: it cannot be emptied from under the role.
        Session admin = signUp("admin@example.com");
        expectError(delete(ME, admin.access()), 409, "INVALID_STATE");
        assertThat(count("select count(*) from users where is_active = true")).isEqualTo(4);
    }

    @Test
    void whoABuyerWasIsDestroyedWhenTheRecordsNoLongerNeedIt() throws Exception {
        Session member = memberWithHistory("old@example.com");
        delete(ME, member.access()).andExpect(status().isOk());
        profiles.purgeExpired();
        assertThat(count("select count(*) from withdrawn_members")).isEqualTo(1);
        assertThat(count("select count(*) from pdf_downloads where ip_address is not null")).isEqualTo(1);

        jdbc.sql("update withdrawn_members set purge_after = ?").param(utc(Instant.now().minusSeconds(60))).update();
        profiles.purgeExpired();
        assertThat(count("select count(*) from withdrawn_members")).isZero();
        assertThat(count("select count(*) from pdf_downloads where ip_address is not null or user_agent is not null")).isZero();
        assertThat(count("select count(*) from orders where user_id = ?", member.userId())).isEqualTo(1);
        // Words the buyer wrote can name people: they go with the name.
        assertThat(jdbc.sql("select reason from refund_requests").query(String.class).single()).isEmpty();
    }

    // A link to a free sheet is a member's link: it ends when the account does, so no download is
    // recorded under an account that has left.
    @Test
    void aLinkToAFreeSheetEndsWithTheAccount() throws Exception {
        Session member = signUp("free@example.com");
        long sheet = activity();
        jdbc.sql("update activities set price = 0 where id = ?").param(sheet).update();
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/test-files/a"));
        java.nio.file.Files.write(java.nio.file.Path.of("build/test-files/a/p.pdf"), "%PDF-1.4\n%%EOF".getBytes());
        String link = ((String) read(post("/api/v1/activities/" + sheet + "/download", member.access(), null)
                .andExpect(status().isOk()), "$.download_url")).replace("http://localhost", "");
        get(link, null).andExpect(status().isOk());
        long recorded = count("select count(*) from pdf_downloads");
        delete(ME, member.access()).andExpect(status().isOk());
        // The sheet itself stays free to everyone; only the member's link, and its record, stop.
        get(link, null).andExpect(status().isOk());
        assertThat(count("select count(*) from pdf_downloads")).isEqualTo(recorded);
    }
}
