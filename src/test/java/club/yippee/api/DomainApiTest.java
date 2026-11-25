package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Children, the catalogue, favorites and the cart, held to the Django server's tests. */
class DomainApiTest extends ApiTestBase {

    private static final String CHILDREN = "/api/v1/children";
    private static final String ACTIVE = "/api/v1/users/me/active-child";
    private static final String ACTIVITIES = "/api/v1/activities";
    private static final String BAD_INPUT = "VALIDATION_ERROR";
    private int clock;

    private long category(String name) {
        jdbc.sql("insert into categories (name) values (?)").param(name).update();
        return count("select max(id) from categories");
    }

    private long activity(String title, boolean published) {
        jdbc.sql("insert into activities (title, description, age_min_months, age_max_months, difficulty, duration_min,"
                        + " duration_max, price, image_path, pdf_path, is_public, created_at, updated_at)"
                        + " values (?, '놀이 설명', 60, 65, 'easy', 5, 5, 0, 'activities/cover.png', 'activities/play.pdf', ?, ?, ?)")
                .params(title, published, utc(Instant.now().plusSeconds(++clock)), utc(Instant.now())).update();
        return count("select max(id) from activities");
    }

    private long child(Session owner, String name) throws Exception {
        Number id = read(post(CHILDREN, owner.access(), q("{'name':'%s','birth_date':'2022-05-01'}".formatted(name)))
                .andExpect(status().isCreated()), "$.id");
        return id.longValue();
    }

    // --- children ---

    @Test
    void childCreateListAndDetail() throws Exception {
        Session owner = signUp("owner@example.com");
        Session other = signUp("other@example.com");
        long first = category("First");
        long second = category("Second");
        ResultActions created = post(CHILDREN, owner.access(),
                q("{'name':' Child ','birth_date':'2022-05-01','gender':'female','interests':[%d,%d]}".formatted(second, first)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.name").value("Child")).andExpect(jsonPath("$.birth_date").value("2022-05-01"))
                .andExpect(jsonPath("$.gender").value("female"))
                .andExpect(jsonPath("$.interests[0]").value(first)).andExpect(jsonPath("$.interests[1]").value(second));
        Number id = read(created, "$.id");
        long minimal = child(owner, "Minimal");
        child(other, "Private");

        get(CHILDREN + "?user_id=" + other.userId(), owner.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].id").value(id)).andExpect(jsonPath("$.results[1].id").value(minimal))
                .andExpect(jsonPath("$.results[1].gender").value("")).andExpect(jsonPath("$.results[1].interests").isEmpty());
        get(CHILDREN + "/" + id, owner.access()).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Child"));
        post(CHILDREN, owner.access(), q("{'name':'N','birth_date':'%s','gender':null,'interests':null}".formatted(LocalDate.now())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gender").value("")).andExpect(jsonPath("$.interests").isEmpty());
        expectError(get(CHILDREN, null), 401, "AUTHENTICATION_REQUIRED");
        expectError(post(CHILDREN, null, q("{'name':'A','birth_date':'2022-05-01'}")), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void childInputIsStrict() throws Exception {
        Session owner = signUp("owner@example.com");
        long known = category("First");
        String date = "'birth_date':'2022-05-01'";
        for (String body : List.of("{}", "{'name':'Only name'}", "{" + date + "}", "{'name':null," + date + "}",
                "{'name':''," + date + "}", "{'name':1234," + date + "}", "{'name':'Child','birth_date':null}",
                "{'name':'Child','birth_date':'2024-02-30'}", "{'name':'Child','birth_date':123}",
                "{'name':'Child','birth_date':'" + LocalDate.now().plusDays(2) + "'}",
                "{'name':'Child'," + date + ",'user_id':1}", "{'name':'Child'," + date + ",'id':9}",
                "{'name':'Child'," + date + ",'gender':{'value':'female'}}",
                "{'name':'" + "가".repeat(101) + "'," + date + "}", "[]")) {
            expectError(post(CHILDREN, owner.access(), q(body)), 400, BAD_INPUT);
        }
        for (String interests : List.of("'1'", "{}", "[true]", "['" + known + "']", "[1.0]", "[-1]", "[0]", "[null]",
                "[1000000000000000000000000000000]", "[" + known + "," + known + "]", "[999999999]", "[" + known + ",999999999]")) {
            ResultActions refused = post(CHILDREN, owner.access(), q("{'name':'Child'," + date + ",'interests':" + interests + "}"));
            expectError(refused, 400, BAD_INPUT).andExpect(jsonPath("$.details.interests").isArray());
        }
        assertThat(count("select count(*) from children")).isZero();
    }

    @Test
    void childPatchKeepsWhatIsLeftOutAndClearsWhatIsNull() throws Exception {
        Session owner = signUp("owner@example.com");
        long first = category("First");
        long second = category("Second");
        Number id = read(post(CHILDREN, owner.access(),
                q("{'name':'Child','birth_date':'2022-05-01','gender':'female','interests':[%d]}".formatted(first))), "$.id");
        String url = CHILDREN + "/" + id;

        patch(url, owner.access(), q("{'gender':'male'}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").value("male")).andExpect(jsonPath("$.interests[0]").value(first));
        patch(url, owner.access(), q("{'name':'Updated','birth_date':'2023-02-28','interests':[%d]}".formatted(second)))
                .andExpect(jsonPath("$.name").value("Updated")).andExpect(jsonPath("$.birth_date").value("2023-02-28"))
                .andExpect(jsonPath("$.interests.length()").value(1)).andExpect(jsonPath("$.interests[0]").value(second));
        patch(url, owner.access(), "{}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated"));

        for (String field : List.of("name", "birth_date")) {
            ResultActions refused = patch(url, owner.access(), q("{'%s':null,'gender':null,'interests':null}".formatted(field)));
            expectError(refused, 400, BAD_INPUT).andExpect(jsonPath("$.details." + field).isArray());
        }
        for (String body : List.of("{'name':123}", "{'gender':true}", "{'interests':{}}", "{'user_id':5}",
                "{'name':'Do not save','interests':[999999999]}", "{'name':'Do not save','interests':[%d,%d]}".formatted(first, first))) {
            expectError(patch(url, owner.access(), q(body)), 400, BAD_INPUT);
        }
        get(url, owner.access()).andExpect(jsonPath("$.name").value("Updated")).andExpect(jsonPath("$.gender").value("male"))
                .andExpect(jsonPath("$.interests[0]").value(second));

        patch(url, owner.access(), q("{'gender':null,'interests':null}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").value("")).andExpect(jsonPath("$.interests").isEmpty())
                .andExpect(jsonPath("$.name").value("Updated"));
    }

    @Test
    void anotherUsersChildDoesNotExistForMe() throws Exception {
        Session owner = signUp("owner@example.com");
        Session other = signUp("other@example.com");
        long mine = child(owner, "Mine");
        long theirs = child(other, "Private");
        String url = CHILDREN + "/" + theirs;
        expectError(get(url, owner.access()), 404, "NOT_FOUND");
        expectError(patch(url, owner.access(), q("{'name':'Attack'}")), 404, "NOT_FOUND");
        expectError(delete(url, owner.access()), 404, "NOT_FOUND");
        get(url, other.access()).andExpect(jsonPath("$.name").value("Private"));

        delete(CHILDREN + "/" + mine, owner.access()).andExpect(status().isNoContent()).andExpect(content().string(""));
        expectError(delete(CHILDREN + "/" + mine, owner.access()), 404, "NOT_FOUND");
        for (String address : List.of("/999999999", "/not-an-id", "/-1", "/", "/99999999999999999999999")) {
            expectError(get(CHILDREN + address, owner.access()), 404, "NOT_FOUND");
        }
        expectError(put(CHILDREN, owner.access(), "{}"), 405, "INVALID_STATE");
        assertThat(count("select count(*) from users")).isEqualTo(2);
    }

    @Test
    void theChosenChildIsRememberedAndClearedWhenThatChildIsDeleted() throws Exception {
        Session owner = signUp("owner@example.com");
        Session other = signUp("other@example.com");
        long first = child(owner, "First");
        long second = child(owner, "Second");
        long foreign = child(other, "Private foreign name");
        String me = "/api/v1/users/me";

        get(me, owner.access()).andExpect(jsonPath("$.active_child_id").isEmpty());
        for (long pick : new long[]{first, second, second}) {
            put(ACTIVE, owner.access(), "{\"child_id\":" + pick + "}").andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$.active_child_id").value(pick));
        }
        get(me, owner.access()).andExpect(jsonPath("$.active_child_id").value(second));
        get(me, other.access()).andExpect(jsonPath("$.active_child_id").isEmpty());

        String missing = read(expectError(put(ACTIVE, owner.access(), "{\"child_id\":9223372036854775807}"), 400, BAD_INPUT), "$.message");
        ResultActions refused = put(ACTIVE, owner.access(), "{\"child_id\":" + foreign + "}");
        expectError(refused, 400, BAD_INPUT).andExpect(jsonPath("$.details.child_id").isArray());
        assertThat(refused.andReturn().getResponse().getContentAsString()).doesNotContain("Private foreign name");
        assertThat((String) read(refused, "$.message")).isEqualTo(missing);
        for (String body : List.of("{}", "[]", "null", "{'child_id':null}", "{'child_id':true}", "{'child_id':'" + first + "'}",
                "{'child_id':" + first + ".0}", "{'child_id':0}", "{'child_id':-1}", "{'child_id':[]}",
                "{'child_id':9223372036854775808}", "{'child_id':" + first + ",'user_id':" + other.userId() + "}")) {
            expectError(put(ACTIVE, owner.access(), q(body)), 400, BAD_INPUT);
        }
        get(me, owner.access()).andExpect(jsonPath("$.active_child_id").value(second));

        delete(CHILDREN + "/" + first, owner.access()).andExpect(status().isNoContent());
        get(me, owner.access()).andExpect(jsonPath("$.active_child_id").value(second));
        delete(CHILDREN + "/" + second, owner.access()).andExpect(status().isNoContent());
        get(me, owner.access()).andExpect(jsonPath("$.active_child_id").isEmpty());

        expectError(put(ACTIVE, null, "{\"child_id\":" + first + "}"), 401, "AUTHENTICATION_REQUIRED");
        for (String method : List.of("GET", "POST", "PATCH", "DELETE")) {
            expectError(call(method, ACTIVE, owner.access(), null), 405, "INVALID_STATE");
        }
    }

    // --- catalogue ---

    @Test
    void categoriesAreOpenToAllButABadTokenIsStill401() throws Exception {
        long first = category("First");
        long second = category("Second");
        get("/api/v1/activity-categories", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].id").value(first)).andExpect(jsonPath("$.results[0].name").value("First"))
                .andExpect(jsonPath("$.results[1].id").value(second)).andExpect(jsonPath("$.results[0].length()").value(2));
        get("/api/v1/activity-categories", signUp("a@example.com").access()).andExpect(status().isOk());
        expectError(get("/api/v1/activity-categories", "invalid"), 401, "AUTHENTICATION_REQUIRED");
        expectError(post("/api/v1/activity-categories", null, q("{'name':'Blocked'}")), 405, "INVALID_STATE");
        expectError(get("/api/v1/activity-categories/1", null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void activityListFiltersPagesAndDetail() throws Exception {
        long category = category("Test category");
        long shown = activity("징검다리", true);
        long hidden = activity("숨긴 활동지", false);
        jdbc.sql("insert into activity_categories (activity_id, category_id) values (?, ?)").params(shown, category).update();

        get(ACTIVITIES, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.next").isEmpty())
                .andExpect(jsonPath("$.previous").isEmpty()).andExpect(jsonPath("$.results[0].id").value(shown));
        get(ACTIVITIES + "/" + shown, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(15))
                .andExpect(jsonPath("$.title").value("징검다리")).andExpect(jsonPath("$.price").value(0))
                .andExpect(jsonPath("$.sale_status").value("on_sale")).andExpect(jsonPath("$.difficulty").value("easy"))
                .andExpect(jsonPath("$.age_min_months").value(60)).andExpect(jsonPath("$.duration_max").value(5))
                .andExpect(jsonPath("$.categories[0]").value(category))
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.startsWith(
                        "http://localhost/api/v1/activities/" + shown + "/files/image?v=")))
                .andExpect(jsonPath("$.pdf").value("http://localhost/api/v1/activities/" + shown + "/files/pdf"));

        for (String query : List.of("q=징검", "category=" + category, "age_years=5", "difficulty=easy", "duration=5-10",
                "sort=title", "price_max=0", "utm_source=x")) {
            get(ACTIVITIES + "?" + query, null).andExpect(jsonPath("$.count").value(1));
        }
        for (String query : List.of("q=없는 제목", "age_years=4", "difficulty=hard", "price_min=1", "category=999", "duration=6-9", "q=%25")) {
            get(ACTIVITIES + "?" + query, null).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
        }
        long newer = activity("두 번째", true);
        get(ACTIVITIES + "?page_size=1", null).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.results[0].id").value(newer))
                .andExpect(jsonPath("$.next").value("http://localhost/api/v1/activities?page_size=1&page=2"));
        get(ACTIVITIES + "?page_size=1&page=2", null).andExpect(jsonPath("$.results[0].id").value(shown))
                .andExpect(jsonPath("$.next").isEmpty())
                .andExpect(jsonPath("$.previous").value("http://localhost/api/v1/activities?page_size=1"));
        expectError(get(ACTIVITIES + "?page=3&page_size=1", null), 404, "NOT_FOUND");

        for (String address : List.of("/" + hidden, "/999999", "/99999999999999999999")) {
            expectError(get(ACTIVITIES + address, null), 404, "NOT_FOUND");
        }
        expectError(post(ACTIVITIES, null, "{}"), 405, "INVALID_STATE");
    }

    @Test
    void badActivityQueriesAre400() throws Exception {
        for (String query : List.of("age_years=bad", "category=painting", "difficulty=medium", "page=0", "page_size=101",
                "duration=10-5", "duration=0-5", "sort=popular", "price_min=2&price_max=1", "q=")) {
            expectError(get(ACTIVITIES + "?" + query, null), 400, BAD_INPUT);
        }
    }

    @Test
    void filesAreServedOnlyWhileTheActivityIsPublic() throws Exception {
        Path folder = Path.of("build/test-files/activities");
        Files.createDirectories(folder);
        Files.write(folder.resolve("play.pdf"), "%PDF-1.4\n%%EOF".getBytes());
        Files.write(folder.resolve("cover.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        long shown = activity("징검다리", true);

        get(ACTIVITIES + "/" + shown + "/files/pdf", null).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"activity-" + shown + ".pdf\""))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes("%PDF-1.4\n%%EOF".getBytes()));
        get(ACTIVITIES + "/" + shown + "/files/image", null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                // A cover may be kept by the browser; a PDF (above) never is.
                .andExpect(header().string("Cache-Control", "public, max-age=86400"));
        expectError(get(ACTIVITIES + "/" + shown + "/files/other", null), 404, "NOT_FOUND");

        Files.delete(folder.resolve("cover.png"));
        expectError(get(ACTIVITIES + "/" + shown + "/files/image", null), 404, "NOT_FOUND");
        jdbc.sql("update activities set pdf_path = '../../build.gradle' where id = ?").param(shown).update();
        expectError(get(ACTIVITIES + "/" + shown + "/files/pdf", null), 404, "NOT_FOUND");
        jdbc.sql("update activities set pdf_path = 'activities/play.pdf', is_public = false where id = ?").param(shown).update();
        expectError(get(ACTIVITIES + "/" + shown + "/files/pdf", null), 404, "NOT_FOUND");
    }

    // --- favorites and cart ---

    @Test
    void favoritesBelongToOneUserAndShowOnlyPublicActivities() throws Exception {
        Session owner = signUp("owner@example.com");
        Session other = signUp("other@example.com");
        long first = activity("공개 활동지", true);
        long second = activity("두 번째 공개 활동지", true);
        long hidden = activity("비공개 활동지", false);
        String url = "/api/v1/favorites";
        String body = "{\"activity_id\":" + first + "}";

        post(url, owner.access(), body).andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.activity_id").value(first)).andExpect(jsonPath("$.message").value("처리되었습니다."));
        expectError(post(url, owner.access(), body), 409, "INVALID_STATE");
        post(url, other.access(), body).andExpect(status().isCreated());
        post(url, owner.access(), "{\"activity_id\":" + second + "}").andExpect(status().isCreated());
        for (String bad : List.of("{}", "{'activity_id':null}", "{'activity_id':true}", "{'activity_id':'1'}", "{'activity_id':1.0}",
                "{'activity_id':-1}", "{'activity_id':0}", "{'activity_id':9223372036854775808}", "[]",
                "{'activity_id':" + first + ",'user_id':" + other.userId() + "}", "{'activity_id':" + hidden + "}", "{'activity_id':999999999}")) {
            expectError(post(url, owner.access(), q(bad)), 400, BAD_INPUT);
        }

        get(url, owner.access()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.results.length()").value(2)).andExpect(jsonPath("$.results[0].id").value(second))
                .andExpect(jsonPath("$.results[1].id").value(first)).andExpect(jsonPath("$.results[0].length()").value(15));
        get(url + "?page=2&page_size=1", owner.access()).andExpect(jsonPath("$.results[0].id").value(first));
        get(url + "?page=3&page_size=1", owner.access()).andExpect(status().isOk()).andExpect(jsonPath("$.results").isEmpty());
        for (String query : List.of("page=0", "page=bad", "page_size=101", "page_size=0", "page=2147483648", "user_id=1", "sort=popular")) {
            expectError(get(url + "?" + query, owner.access()), 400, BAD_INPUT);
        }

        jdbc.sql("update activities set is_public = false where id = ?").param(second).update();
        get(url, owner.access()).andExpect(jsonPath("$.results.length()").value(1));
        expectError(post(url, other.access(), "{\"activity_id\":" + second + "}"), 400, BAD_INPUT);
        delete(url + "/" + second, owner.access()).andExpect(status().isNoContent()).andExpect(content().string(""));
        expectError(delete(url + "/" + second, owner.access()), 404, "NOT_FOUND");
        for (String id : List.of("0", "9223372036854775808")) {
            expectError(delete(url + "/" + id, owner.access()), 400, BAD_INPUT);
        }
        delete(url + "/" + first, owner.access()).andExpect(status().isNoContent());
        get(url, other.access()).andExpect(jsonPath("$.results.length()").value(1));
        for (ResultActions closed : List.of(get(url, null), post(url, null, body), delete(url + "/" + first, null), get(url, owner.refresh()))) {
            expectError(closed, 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void theCartListsNewestFirstAndAddsUpStoredPrices() throws Exception {
        Session owner = signUp("owner@example.com");
        Session other = signUp("other@example.com");
        long first = activity("장바구니 공개 활동지", true);
        long second = activity("두 번째 장바구니 활동지", true);
        long hidden = activity("장바구니 비공개 활동지", false);
        String items = "/api/v1/cart/items";

        get("/api/v1/cart", owner.access()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.total_amount").value(0));
        post(items, owner.access(), "{\"activity_id\":" + first + "}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.activity_id").value(first)).andExpect(jsonPath("$.message").value("처리되었습니다."));
        expectError(post(items, owner.access(), "{\"activity_id\":" + first + "}"), 409, "INVALID_STATE");
        post(items, owner.access(), "{\"activity_id\":" + second + "}").andExpect(status().isCreated());
        post(items, other.access(), "{\"activity_id\":" + first + "}").andExpect(status().isCreated());
        for (String bad : List.of("{}", "[]", "{'activity_id':'1'}", "{'activity_id':" + first + ",'price':1}",
                "{'activity_id':" + first + ",'total_amount':1}", "{'activity_id':" + hidden + "}")) {
            expectError(post(items, owner.access(), q(bad)), 400, BAD_INPUT);
        }

        get("/api/v1/cart", owner.access()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second)).andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.items[0].length()").value(15)).andExpect(jsonPath("$.total_amount").value(0));
        get("/api/v1/cart", other.access()).andExpect(jsonPath("$.items.length()").value(1));
        for (String query : List.of("user_id=1", "page=1", "sort=price")) {
            expectError(get("/api/v1/cart?" + query, owner.access()), 400, BAD_INPUT);
        }

        delete(items + "/" + first, owner.access()).andExpect(status().isNoContent()).andExpect(content().string(""));
        expectError(delete(items + "/" + first, owner.access()), 404, "NOT_FOUND");
        expectError(delete(items + "/0", owner.access()), 400, BAD_INPUT);
        get("/api/v1/cart", other.access()).andExpect(jsonPath("$.items.length()").value(1));
        expectError(get("/api/v1/cart", null), 401, "AUTHENTICATION_REQUIRED");
    }
}
