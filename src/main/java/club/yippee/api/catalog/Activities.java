package club.yippee.api.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Digests;

/** The public catalogue of activities, and the files behind each one. */
@Service
public class Activities {

    private static final Map<String, Sort> ORDERS = Map.of(
            "latest", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
            "oldest", Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")),
            "title", Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")),
            "price_asc", Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id")),
            "price_desc", Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id")));

    /** How long a 7-day pass lasts, counted from the moment its payment is settled. */
    public static final int RENTAL_DAYS = 7;
    /** How long a download link stays good. */
    static final int LINK_SECONDS = 300;
    private static final String DOWNLOAD = "download";

    private final ActivityRepository repository;
    private final Digests digests;
    private final JdbcClient jdbc;
    private final Path filesRoot;

    Activities(ActivityRepository repository, Digests digests, JdbcClient jdbc, AppProperties properties) {
        this.repository = repository;
        this.digests = digests;
        this.jdbc = jdbc;
        // shortcut: files live on this server's disk, so only one server can serve them.
        // Move them to private object storage (S3) before running a second instance.
        this.filesRoot = Path.of(properties.filesRoot()).toAbsolutePath().normalize();
    }

    static boolean knownSort(String sort) {
        return ORDERS.containsKey(sort);
    }

    /** What a list request asks for. Every value is already validated; null means "no filter". */
    record Search(String text, Long category, Integer ageYears, Integer durationLow, Integer durationHigh,
                  String difficulty, Integer priceMin, Integer priceMax, String sort, int page, int pageSize) {
    }

    Page<Activity> search(Search search) {
        Specification<Activity> filter = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.isTrue(root.get("published")));
            if (search.text() != null) {
                String pattern = "%" + search.text().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                all.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (search.category() != null) {
                all.add(cb.isMember(search.category(), root.get("categoryIds")));
            }
            if (search.ageYears() != null) {
                // The year of age as a span of months, overlapping the activity's recommended span.
                int months = search.ageYears() * 12;
                all.add(cb.lessThanOrEqualTo(root.get("ageMinMonths"), months + 11));
                all.add(cb.greaterThanOrEqualTo(root.get("ageMaxMonths"), months));
            }
            if (search.durationLow() != null) {
                all.add(cb.lessThanOrEqualTo(root.get("durationMin"), search.durationHigh()));
                all.add(cb.greaterThanOrEqualTo(root.get("durationMax"), search.durationLow()));
            }
            if (search.difficulty() != null) {
                all.add(cb.equal(root.get("difficulty"), search.difficulty()));
            }
            if (search.priceMin() != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("price"), search.priceMin()));
            }
            if (search.priceMax() != null) {
                all.add(cb.lessThanOrEqualTo(root.get("price"), search.priceMax()));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
        return repository.findAll(filter, PageRequest.of(search.page() - 1, search.pageSize(), ORDERS.get(search.sort())));
    }

    public Optional<Activity> findPublic(Long id) {
        return repository.findByIdAndPublishedTrue(id);
    }

    /** Published or not. For owners and for the file check, never for the public catalogue. */
    public Optional<Activity> find(Long id) {
        return repository.findById(id);
    }

    /** A buyer's activities as JSON, in the order given. What was bought stays listed after it is unpublished. */
    public List<Map<String, Object>> viewsOfOwned(List<Long> ids) {
        Map<Long, Activity> found = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(Activity::id, Function.identity()));
        return ids.stream().map(found::get).filter(row -> row != null).map(this::view).toList();
    }

    /**
     * A link to the PDF that works for a few minutes without a login header, so a browser can
     * simply open it. It is signed for this user and this activity.
     */
    public Map<String, Object> downloadLink(Long userId, Activity activity) {
        Instant expires = Instant.now().plusSeconds(LINK_SECONDS);
        String payload = userId + ":" + activity.id + ":" + expires.getEpochSecond();
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + digests.of(DOWNLOAD, payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("download_url", fileUrl(activity.id, "pdf") + "?token=" + token);
        body.put("expires_at", expires.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        body.put("file_name", "activity-" + activity.id + ".pdf");
        return body;
    }

    /**
     * Whether this user may have the PDF at this moment: "ok", or why not ("none", "expired", "refund").
     * The one rule for both handing out a link and sending the file, so a link issued earlier stops
     * working the moment the right ends: the 7 days ran out, the order was refunded, the account was closed.
     * While a refund request waits nothing is sent either, so what the admin reads cannot change under them.
     */
    public String downloadState(Long userId, Activity activity) {
        if (activity.published && activity.price == 0) {
            // Free to everyone, but a link is a member's: it ends when the account does.
            return jdbc.sql("select count(*) from users where id = ? and is_active = true").param(userId)
                    .query(Long.class).single() > 0 ? "ok" : "none";
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        return jdbc.sql("select e.expires_at, (select count(*) from refund_requests r where r.order_id = e.order_id"
                        + " and r.status = 'requested') as asking from purchase_entitlements e"
                        + " join users u on u.id = e.user_id"
                        + " where e.user_id = ? and e.activity_id = ? and e.status = 'active' and u.is_active = true")
                .params(userId, activity.id)
                .query((row, index) -> {
                    java.time.LocalDateTime expires = row.getObject("expires_at", java.time.LocalDateTime.class);
                    if (expires != null && !expires.isAfter(now)) {
                        return "expired";
                    }
                    return row.getLong("asking") > 0 ? "refund" : "ok";
                }).optional().orElse("none");
    }

    /** The user an unexpired download link of ours was issued to, for this activity; null for anything else. */
    Long downloadUser(Long activityId, String token) {
        try {
            int dot = token.lastIndexOf('.');
            String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            String[] parts = payload.split(":");
            boolean ours = parts.length == 3 && Long.parseLong(parts[1]) == activityId
                    && Long.parseLong(parts[2]) > Instant.now().getEpochSecond()
                    && Digests.same(token.substring(dot + 1), digests.of(DOWNLOAD, payload));
            return ours ? Long.valueOf(parts[0]) : null;
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return null;
        }
    }

    /** The public activity, row-locked for the caller's transaction. */
    public Optional<Activity> lockPublic(Long id) {
        return repository.lockPublic(id);
    }

    /** The public activities among these ids, as JSON, in the order the ids were given. */
    public List<Map<String, Object>> viewsInOrder(List<Long> ids) {
        Map<Long, Activity> found = repository.findByIdInAndPublishedTrue(ids).stream()
                .collect(Collectors.toMap(Activity::id, Function.identity()));
        return ids.stream().map(found::get).filter(row -> row != null).map(this::view).toList();
    }

    /** The JSON shape shared by the catalogue, favorites and the cart. Needs a current request for the links. */
    public Map<String, Object> view(Activity activity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", activity.id);
        body.put("title", activity.title);
        body.put("price", activity.price);
        // Null when the sheet is sold for good only.
        body.put("rental_price", activity.rentalPrice);
        body.put("rental_days", RENTAL_DAYS);
        body.put("sale_status", "on_sale");
        body.put("description", activity.description);
        body.put("age_min_months", activity.ageMinMonths);
        body.put("age_max_months", activity.ageMaxMonths);
        body.put("difficulty", activity.difficulty);
        body.put("duration_min", activity.durationMin);
        body.put("duration_max", activity.durationMax);
        body.put("categories", new TreeSet<>(activity.categoryIds));
        // The version changes when the activity does, so a cached cover is never a stale one.
        body.put("image", fileUrl(activity.id, "image") + "?v=" + activity.updatedAt.getEpochSecond());
        body.put("pdf", fileUrl(activity.id, "pdf"));
        return body;
    }

    // The link never shows where the file is stored: it leads back through this server's checks.
    private static String fileUrl(Long id, String kind) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/activities/{id}/files/{kind}").buildAndExpand(id, kind).toUriString();
    }

    /**
     * What an admin sends for a new activity or a change. A null part is left as it is.
     * rentalPrice 0 means "no 7-day pass" (a real one is never 0), so it can be taken away again.
     */
    public record Draft(String title, String description, Integer ageMinMonths, Integer ageMaxMonths,
                        String difficulty, Integer durationMin, Integer durationMax, Integer price,
                        Integer rentalPrice, Boolean published, List<Long> categoryIds) {
    }

    /** Stores a new activity and its two files. */
    @Transactional
    public Map<String, Object> register(Draft draft, byte[] image, String imageExtension, byte[] pdf) throws IOException {
        Activity activity = apply(new Activity(), draft);
        activity.imagePath = store(image, imageExtension);
        activity.pdfPath = store(pdf, "pdf");
        return adminView(repository.save(activity));
    }

    /** Changes an activity under its row lock, so a cart or favorite being added sees one state or the other. */
    @Transactional
    public Map<String, Object> revise(Long id, Draft draft) {
        Activity activity = apply(repository.lockAny(id).orElseThrow(ApiException::notFound), draft);
        activity.updatedAt = Instant.now();
        return adminView(activity);
    }

    /** Swaps one or both files of an activity. The old files are removed only once the change is committed. */
    @Transactional
    public Map<String, Object> replaceFiles(Long id, byte[] image, String imageExtension, byte[] pdf) throws IOException {
        Activity activity = repository.lockAny(id).orElseThrow(ApiException::notFound);
        List<String> replaced = new ArrayList<>();
        if (image != null) {
            replaced.add(activity.imagePath);
            activity.imagePath = store(image, imageExtension);
        }
        if (pdf != null) {
            replaced.add(activity.pdfPath);
            activity.pdfPath = store(pdf, "pdf");
        }
        activity.updatedAt = Instant.now();
        removeAfterCommit(replaced);
        return adminView(activity);
    }

    /** The most sample pages one activity may have. */
    public static final int MAX_PREVIEWS = 5;

    /**
     * Replaces the activity's sample pages with these images, in this order. An empty list takes
     * them all away. The old files are removed only once the change is committed.
     */
    @Transactional
    public Map<String, Object> replacePreviews(Long id, List<byte[]> images, List<String> extensions) throws IOException {
        Activity activity = repository.lockAny(id).orElseThrow(ApiException::notFound);
        List<String> replaced = jdbc.sql("select path from activity_previews where activity_id = ?")
                .param(id).query(String.class).list();
        jdbc.sql("delete from activity_previews where activity_id = ?").param(id).update();
        for (int index = 0; index < images.size(); index++) {
            jdbc.sql("insert into activity_previews (activity_id, position, path) values (?, ?, ?)")
                    .params(id, index + 1, store(images.get(index), extensions.get(index))).update();
        }
        // The links carry this moment, so a browser never shows an old page under a new set.
        activity.updatedAt = Instant.now();
        removeAfterCommit(replaced);
        return previews(activity);
    }

    /** Links to the activity's sample pages, in order. They lead back through this server. */
    public Map<String, Object> previews(Activity activity) {
        List<String> links = jdbc.sql("select position from activity_previews where activity_id = ? order by position")
                .param(activity.id).query(Integer.class).list().stream()
                .map(position -> ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/activities/{id}/previews/{position}").buildAndExpand(activity.id, position)
                        .toUriString() + "?v=" + activity.updatedAt.getEpochSecond())
                .toList();
        return Map.of("preview_urls", links);
    }

    /** One stored sample page, or null when there is none at that place. */
    Resource preview(Activity activity, int position) {
        return jdbc.sql("select path from activity_previews where activity_id = ? and position = ?")
                .params(activity.id, position).query(String.class).optional().map(this::stored).orElse(null);
    }

    private void removeAfterCommit(List<String> names) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (String name : names) {
                            try {
                                Files.deleteIfExists(filesRoot.resolve(name).normalize());
                            } catch (IOException leftBehind) {
                                // An unused file left on disk harms nothing; the activity already points at the new one.
                            }
                        }
                    }
                });
    }

    private Activity apply(Activity activity, Draft draft) {
        if (draft.title() != null) {
            activity.title = draft.title();
        }
        if (draft.description() != null) {
            activity.description = draft.description();
        }
        if (draft.ageMinMonths() != null) {
            activity.ageMinMonths = draft.ageMinMonths();
        }
        if (draft.ageMaxMonths() != null) {
            activity.ageMaxMonths = draft.ageMaxMonths();
        }
        if (draft.difficulty() != null) {
            activity.difficulty = draft.difficulty();
        }
        if (draft.durationMin() != null) {
            activity.durationMin = draft.durationMin();
        }
        if (draft.durationMax() != null) {
            activity.durationMax = draft.durationMax();
        }
        if (draft.price() != null) {
            activity.price = draft.price();
        }
        if (draft.rentalPrice() != null) {
            activity.rentalPrice = draft.rentalPrice() == 0 ? null : draft.rentalPrice();
        }
        // Checked on the result, so lowering the price under the 7-day price is caught as well.
        if (activity.rentalPrice != null && (activity.rentalPrice <= 0 || activity.rentalPrice >= activity.price)) {
            throw ApiException.field("rental_price");
        }
        if (draft.published() != null) {
            activity.published = draft.published();
        }
        if (activity.ageMaxMonths < activity.ageMinMonths) {
            throw ApiException.field("age_max_months");
        }
        if (activity.durationMax < activity.durationMin) {
            throw ApiException.field("duration_max");
        }
        if (draft.categoryIds() != null) {
            List<Long> wanted = draft.categoryIds();
            if (wanted.isEmpty() || jdbc.sql("select count(*) from categories where id in (:ids)")
                    .param("ids", wanted).query(Long.class).single() != wanted.size()) {
                throw ApiException.field("categories");
            }
            activity.categoryIds.clear();
            activity.categoryIds.addAll(wanted);
        }
        return activity;
    }

    // The stored name is ours alone: nothing from the uploaded file's name reaches the disk.
    private String store(byte[] bytes, String extension) throws IOException {
        String name = "activities/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = filesRoot.resolve(name);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return name;
    }

    private Map<String, Object> adminView(Activity activity) {
        Map<String, Object> body = view(activity);
        body.put("is_public", activity.published);
        return body;
    }

    /** The stored file, or null when it is not there. A stored path can never lead outside the root. */
    Resource file(Activity activity, String kind) {
        return stored("pdf".equals(kind) ? activity.pdfPath : activity.imagePath);
    }

    private Resource stored(String name) {
        Path path = filesRoot.resolve(name).normalize();
        return path.startsWith(filesRoot) && Files.isRegularFile(path) ? new FileSystemResource(path) : null;
    }

    static String extension(Resource file) throws IOException {
        String name = file.getFilename() == null ? "" : file.getFilename();
        return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "bin";
    }
}
