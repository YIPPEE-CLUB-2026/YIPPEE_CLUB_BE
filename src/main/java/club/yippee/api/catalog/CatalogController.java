package club.yippee.api.catalog;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import club.yippee.api.common.ApiException;

/** Read-only and open to everyone: categories, the activity list and detail, and each activity's files. */
@RestController
public class CatalogController {

    private static final Pattern WHOLE = Pattern.compile("[0-9]{1,9}");
    private static final Pattern RANGE = Pattern.compile("[0-9]{1,5}-[0-9]{1,5}");
    static final Map<String, MediaType> TYPES = Map.of("pdf", MediaType.APPLICATION_PDF,
            "png", MediaType.IMAGE_PNG, "jpg", MediaType.IMAGE_JPEG, "jpeg", MediaType.IMAGE_JPEG,
            "webp", MediaType.parseMediaType("image/webp"));

    private final Activities activities;
    private final Issuances issuances;
    private final JdbcClient jdbc;

    CatalogController(Activities activities, Issuances issuances, JdbcClient jdbc) {
        this.activities = activities;
        this.issuances = issuances;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/activity-categories")
    Map<String, Object> categories() {
        List<Map<String, Object>> rows = jdbc.sql("select id, name from categories order by id")
                .query((row, index) -> Map.<String, Object>of("id", row.getLong("id"), "name", row.getString("name")))
                .list();
        return Map.of("results", rows);
    }

    @GetMapping("/api/v1/activities")
    Map<String, Object> list(@RequestParam Map<String, String> params) {
        Activities.Search search = parse(params);
        Page<Activity> page = activities.search(search);
        if (search.page() > 1 && page.getContent().isEmpty()) {
            throw ApiException.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", page.getTotalElements());
        body.put("next", page.hasNext() ? pageLink(search.page() + 1) : null);
        body.put("previous", search.page() > 1 ? pageLink(search.page() - 1) : null);
        body.put("results", page.getContent().stream().map(activities::view).toList());
        return body;
    }

    // The same address with another page number. Page 1 is the address without one.
    private static String pageLink(int page) {
        return ServletUriComponentsBuilder.fromCurrentRequest()
                .replaceQueryParam("page", page > 1 ? new Object[]{page} : new Object[0]).toUriString();
    }

    @GetMapping("/api/v1/activities/{id:[0-9]+}")
    Map<String, Object> detail(@PathVariable String id) {
        return activities.view(visible(id));
    }

    @GetMapping("/api/v1/activities/{id:[0-9]+}/files/{kind}")
    ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String kind,
                                  @RequestParam(required = false) String token,
                                  org.springframework.http.HttpMethod method,
                                  jakarta.servlet.http.HttpServletRequest request) throws IOException {
        Activity activity = (id.length() <= 18 ? activities.find(Long.valueOf(id)) : java.util.Optional.<Activity>empty())
                .orElseThrow(ApiException::notFound);
        // A download link (issued to a member) opens that member's coded copy of the PDF whatever the
        // price or publication state. Without one, only published activities are served, and a PDF
        // only when it is free (the original, with no code on it).
        Issuances.Issued issued = "pdf".equals(kind) && token != null ? issuances.open(activity.id, token) : null;
        // Checked again now, not only when the link was made: the right may have ended in between.
        boolean linked = issued != null && "ok".equals(activities.downloadState(issued.userId(), activity));
        if (!linked && (!activity.published || ("pdf".equals(kind) && activity.price > 0))) {
            throw ApiException.notFound();
        }
        Resource file = linked ? issuances.copy(issued, activity)
                : "pdf".equals(kind) || "image".equals(kind) ? activities.file(activity, kind) : null;
        if (file == null) {
            throw ApiException.notFound();
        }
        // A HEAD request reaches this method too and receives no file, so it leaves no record:
        // the record is read as proof that the buyer took the file.
        if (linked && method == org.springframework.http.HttpMethod.GET) {
            // Recorded before a single byte leaves. If the record cannot be written the file is not
            // sent, so there is never a delivered PDF without its record.
            // With the address and browser it went to: a link that was passed on can be told apart.
            String agent = request.getHeader(HttpHeaders.USER_AGENT);
            jdbc.sql("insert into pdf_downloads (user_id, activity_id, issuance_id, downloaded_at, ip_address, user_agent)"
                            + " values (?, ?, ?, ?, ?, ?)")
                    .params(issued.userId(), activity.id, issued.id(), java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                            request.getRemoteAddr(), agent == null || agent.length() <= 255 ? agent : agent.substring(0, 255))
                    .update();
        }
        String extension = Activities.extension(file);
        // A cover is public and its address changes whenever the file does, so a browser may keep it
        // for a day instead of fetching it on every visit. A PDF is never cached.
        boolean cover = "image".equals(kind);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, cover ? "public, max-age=86400" : "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, (cover ? "inline" : "attachment")
                        // A coded copy is named by its code, which says nothing about the member.
                        + "; filename=\"" + (linked ? issued.code() : "activity-" + activity.id) + "." + extension + "\"")
                .contentType(TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM))
                .body(file);
    }

    /** Links to the sample pages of an activity on the catalogue. An empty list when it has none. */
    @GetMapping("/api/v1/activities/{id:[0-9]+}/preview")
    Map<String, Object> preview(@PathVariable String id) {
        return activities.previews(visible(id));
    }

    @GetMapping("/api/v1/activities/{id:[0-9]+}/previews/{position:[1-9]}")
    ResponseEntity<Resource> previewPage(@PathVariable String id, @PathVariable int position) throws IOException {
        Resource file = activities.preview(visible(id), position);
        if (file == null) {
            throw ApiException.notFound();
        }
        // Public, and its address changes with the set, so a browser may keep it for a day.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(TYPES.getOrDefault(Activities.extension(file), MediaType.APPLICATION_OCTET_STREAM))
                .body(file);
    }

    // Unpublished and missing look the same from outside.
    private Activity visible(String id) {
        Long number = id.length() <= 18 ? Long.valueOf(id) : null;
        return (number == null ? java.util.Optional.<Activity>empty() : activities.findPublic(number))
                .orElseThrow(ApiException::notFound);
    }

    private static Activities.Search parse(Map<String, String> params) {
        Set<String> faults = new LinkedHashSet<>();
        String text = params.get("q");
        if (text != null && (text.isBlank() || text.length() > 255)) {
            faults.add("q");
        }
        Integer category = whole(params, "category", 1, Integer.MAX_VALUE, faults);
        Integer ageYears = whole(params, "age_years", 0, 5460, faults);
        Integer low = null;
        Integer high = null;
        String duration = params.get("duration");
        if (duration != null) {
            if (RANGE.matcher(duration).matches()) {
                low = Integer.valueOf(duration.substring(0, duration.indexOf('-')));
                high = Integer.valueOf(duration.substring(duration.indexOf('-') + 1));
            }
            if (low == null || low < 1 || high < low || high > 65535) {
                faults.add("duration");
            }
        }
        String difficulty = params.get("difficulty");
        if (difficulty != null && !Activities.DIFFICULTIES.contains(difficulty)) {
            faults.add("difficulty");
        }
        Integer priceMin = whole(params, "price_min", 0, Integer.MAX_VALUE, faults);
        Integer priceMax = whole(params, "price_max", 0, Integer.MAX_VALUE, faults);
        if (priceMin != null && priceMax != null && priceMin > priceMax) {
            faults.add("price_max");
        }
        String sort = params.getOrDefault("sort", "latest");
        if (!Activities.knownSort(sort)) {
            faults.add("sort");
        }
        Integer page = whole(params, "page", 1, Integer.MAX_VALUE, faults);
        Integer pageSize = whole(params, "page_size", 1, 100, faults);
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        return new Activities.Search(text == null ? null : text.strip(), category == null ? null : category.longValue(),
                ageYears, low, high, difficulty, priceMin, priceMax, sort,
                page == null ? 1 : page, pageSize == null ? 20 : pageSize);
    }

    /** A whole number within bounds, null when the parameter is absent; anything else is a fault. */
    public static Integer whole(Map<String, String> params, String name, int min, int max, Set<String> faults) {
        String raw = params.get(name);
        if (raw == null) {
            return null;
        }
        if (WHOLE.matcher(raw).matches()) {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                return value;
            }
        }
        faults.add(name);
        return null;
    }
}
