package club.yippee.api.catalog;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Digests;

/**
 * The code printed on a member's copy of a sheet.
 *
 * - One code per member and sheet, for good: downloading again prints the same one, and so does
 *   buying again after a refund. A new code would be a way round "one photo per copy".
 * - The code is the sheet's number and twelve random characters. Nothing of the member is in it,
 *   and no address open to members takes a code: only an admin can look one up.
 * - A member is only ever sent the coded copy. If it cannot be made, nothing is sent; the original
 *   never goes out in its place.
 */
@Service
public class Issuances {

    private static final Logger log = LoggerFactory.getLogger(Issuances.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Without 0, 1, I and O, which a camera and a parent both misread. */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LINK = "download";
    private static final float SIZE = 10f;
    private static final float MARGIN = 6f * 72f / 25.4f;   // 6 mm from the bottom and left edges

    /** One issued copy: its row, whose it is, and the code on it. */
    public record Issued(long id, long userId, String code) {
    }

    private final Activities activities;
    private final Digests digests;
    private final JdbcClient jdbc;
    private final Path issuedRoot;

    Issuances(Activities activities, Digests digests, JdbcClient jdbc, AppProperties properties) {
        this.activities = activities;
        this.digests = digests;
        this.jdbc = jdbc;
        this.issuedRoot = Path.of(properties.filesRoot()).toAbsolutePath().normalize().resolve("issued");
    }

    /**
     * A short-lived link to this member's coded copy. The caller has already checked the right to it.
     * The copy is made before the link is handed out, so a link never leads to a file that cannot be made.
     */
    public Map<String, Object> link(Long userId, Activity activity) {
        Issued issued = issue(userId, activity.id);
        copy(issued, activity);
        Instant expires = Instant.now().plusSeconds(Activities.LINK_SECONDS);
        // The link names the copy, not the member: decoding it shows no user number.
        String said = issued.id() + ":" + expires.getEpochSecond();
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(said.getBytes(StandardCharsets.UTF_8))
                + "." + digests.of(LINK, said);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("download_url", Activities.fileUrl(activity.id, "pdf") + "?token=" + token);
        body.put("expires_at", expires.truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("file_name", issued.code() + ".pdf");
        body.put("issuance_code", issued.code());
        return body;
    }

    /** The copy an unexpired link of ours was made for, if it is a copy of this activity; null for anything else. */
    Issued open(Long activityId, String token) {
        try {
            int dot = token.lastIndexOf('.');
            String said = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            String[] parts = said.split(":");
            boolean ours = parts.length == 2 && Long.parseLong(parts[1]) > Instant.now().getEpochSecond()
                    && Digests.same(token.substring(dot + 1), digests.of(LINK, said));
            return !ours ? null : jdbc.sql("select id, user_id, serial_code from worksheet_issuances where id = ? and activity_id = ?")
                    .params(Long.valueOf(parts[0]), activityId).query(ISSUED).optional().orElse(null);
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return null;
        }
    }

    private static final RowMapper<Issued> ISSUED = (row, index) ->
            new Issued(row.getLong("id"), row.getLong("user_id"), row.getString("serial_code"));

    private Optional<Issued> find(Long userId, Long activityId) {
        return jdbc.sql("select id, user_id, serial_code from worksheet_issuances where user_id = ? and activity_id = ?")
                .params(userId, activityId).query(ISSUED).optional();
    }

    // The member's code for this sheet, made on the first request. Two requests at once end with one
    // row: the table allows one per member and sheet, and the loser reads the winner's.
    private Issued issue(Long userId, Long activityId) {
        Optional<Issued> known = find(userId, activityId);
        if (known.isPresent()) {
            return known.get();
        }
        // The order the right came from. A free sheet taken without an order has none.
        Long orderId = jdbc.sql("select order_id from purchase_entitlements where user_id = ? and activity_id = ?"
                + " and status = 'active'").params(userId, activityId).query(Long.class).optional().orElse(null);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                jdbc.sql("insert into worksheet_issuances (serial_code, user_id, activity_id, order_id, issued_at)"
                                + " values (?, ?, ?, ?, ?)")
                        .params(newCode(activityId), userId, activityId, orderId, LocalDateTime.now(ZoneOffset.UTC)).update();
            } catch (DuplicateKeyException taken) {
                // Either another request of this member's got there first (read below), or the random
                // part met an existing code (the next round draws another).
            }
            known = find(userId, activityId);
            if (known.isPresent()) {
                return known.get();
            }
        }
        throw new IllegalStateException("no free serial code");
    }

    // The sheet's number, then twelve characters drawn from the system's random source and from nothing else.
    static String newCode(Long activityId) {
        StringBuilder code = new StringBuilder("W%04d".formatted(activityId));
        for (int place = 0; place < 12; place++) {
            code.append(place % 4 == 0 ? "-" : "").append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * The member's coded copy of the activity's PDF, made if it is not there yet (the first time, or
     * after the admin replaced the PDF). Never the original.
     */
    Resource copy(Issued issued, Activity activity) {
        Resource original = activities.file(activity, "pdf");
        try {
            if (original == null) {
                throw new IOException("the original is missing");
            }
            // Named by the code and by the original it was made from, so a replaced PDF gets a fresh copy.
            // shortcut: copies of a replaced or refunded sheet stay on the disk. Sweep the folder if it grows.
            Path target = issuedRoot.resolve(issued.code() + "-" + original.getFilename());
            if (!Files.isRegularFile(target)) {
                make(original.getFile(), target, issued.code());
            }
            return new FileSystemResource(target);
        } catch (IOException | RuntimeException failed) {
            log.error("issued_copy_failed issuance={} activity={}", issued.id(), activity.id, failed);
            throw new ApiException(500, "PDF_GENERATION_FAILED", "파일을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    // One at a time: a PDF being rewritten takes memory, and this server is small.
    // Written beside its final place and moved in whole, so a half-written copy is never served.
    private static synchronized void make(File original, Path target, String code) throws IOException {
        if (Files.isRegularFile(target)) {
            return;   // another request made it while this one waited
        }
        Files.createDirectories(target.getParent());
        Path draft = Files.createTempFile(target.getParent(), "draft", ".pdf");
        try {
            stamp(original, draft.toFile(), code);
            Files.move(draft, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(draft);
        }
    }

    /** Whether our code can be printed on this PDF. An upload that fails this is refused. */
    static boolean stampable(byte[] pdf) {
        try {
            Path folder = Files.createTempDirectory("pdf-check");
            Path original = folder.resolve("original.pdf");
            Path copy = folder.resolve("copy.pdf");
            try {
                Files.write(original, pdf);
                stamp(original.toFile(), copy.toFile(), newCode(0L));
                return true;
            } finally {
                Files.deleteIfExists(original);
                Files.deleteIfExists(copy);
                Files.deleteIfExists(folder);
            }
        } catch (IOException | RuntimeException unreadable) {
            // Said in the log, so a fault of this server (a full disk) is not taken for a bad file.
            log.warn("pdf_not_stampable cause={}", unreadable.toString());
            return false;
        }
    }

    /**
     * Writes a copy of the PDF with "CODE: <code>" in the bottom left corner of every page, as the
     * reader sees the page (a page stored turned is allowed for). The original file is only read.
     * That corner is the one our sheets leave empty (the top right holds the name box, the bottom
     * right our mark); nothing is erased to make room.
     */
    static void stamp(File original, File copy, String code) throws IOException {
        try (PDDocument document = Loader.loadPDF(original, IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted() || document.getNumberOfPages() == 0) {
                throw new IOException("a locked or empty PDF");
            }
            PDFont font = new PDType1Font(Standard14Fonts.FontName.COURIER);
            String text = "CODE: " + code;
            float width = font.getStringWidth(text) / 1000f * SIZE;
            for (PDPage page : document.getPages()) {
                PDRectangle box = page.getCropBox();
                int turn = Math.floorMod(page.getRotation(), 360);
                boolean sideways = turn == 90 || turn == 270;
                // The corner as the reader sees it, measured from the page's bottom left as seen.
                float x = MARGIN;
                float y = MARGIN;
                if (x + width + MARGIN > (sideways ? box.getHeight() : box.getWidth())
                        || y + SIZE + MARGIN > (sideways ? box.getWidth() : box.getHeight())) {
                    throw new IOException("a page too small for the code");
                }
                // The same point on the page as it is stored, before the viewer turns it.
                float across = switch (turn) {
                    case 90 -> box.getWidth() - y;
                    case 180 -> box.getWidth() - x;
                    case 270 -> y;
                    default -> x;
                };
                float up = switch (turn) {
                    case 90 -> x;
                    case 180 -> box.getHeight() - y;
                    case 270 -> box.getHeight() - x;
                    default -> y;
                };
                try (PDPageContentStream stream = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText();
                    stream.setFont(font, SIZE);
                    stream.setNonStrokingColor(Color.DARK_GRAY);
                    stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(turn),
                            box.getLowerLeftX() + across, box.getLowerLeftY() + up));
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(copy);
        }
    }

    // --- for the people running the service (admin only) ---

    private static final String ROWS = " from worksheet_issuances i join activities a on a.id = i.activity_id"
            + " join users u on u.id = i.user_id"
            + " left join purchase_entitlements e on e.user_id = i.user_id and e.activity_id = i.activity_id";

    private static final String COLUMNS = "select i.id, i.serial_code, i.user_id, i.activity_id, a.title, i.order_id,"
            + " i.issued_at, u.email, e.status as right_status, e.expires_at,"
            + " (select count(*) from pdf_downloads d where d.issuance_id = i.id) as download_count,"
            + " (select max(d.downloaded_at) from pdf_downloads d where d.issuance_id = i.id) as last_downloaded_at";

    // The member's e-mail is read for the detail only; the list leaves it out.
    private static Map<String, Object> view(java.sql.ResultSet row, boolean withEmail) throws java.sql.SQLException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.getLong("id"));
        body.put("issuance_code", row.getString("serial_code"));
        body.put("user_id", row.getLong("user_id"));
        if (withEmail) {
            body.put("email", row.getString("email"));
        }
        body.put("activity_id", row.getLong("activity_id"));
        body.put("activity_title", row.getString("title"));
        body.put("order_id", row.getObject("order_id", Long.class));
        body.put("issued_at", iso(row.getObject("issued_at", LocalDateTime.class)));
        // What the member holds of the sheet now: active, expired (a 7-day pass that ran out),
        // revoked (refunded), or free (a free sheet, held without an order).
        String status = row.getString("right_status");
        LocalDateTime expires = row.getObject("expires_at", LocalDateTime.class);
        body.put("right", status == null ? "free" : !status.equals("active") ? "revoked"
                : expires != null && !expires.isAfter(LocalDateTime.now(ZoneOffset.UTC)) ? "expired" : "active");
        body.put("download_count", row.getLong("download_count"));
        body.put("last_downloaded_at", iso(row.getObject("last_downloaded_at", LocalDateTime.class)));
        return body;
    }

    private static String iso(LocalDateTime utcTime) {
        return utcTime == null ? null : utcTime + "Z";
    }

    /** A code as it may be typed or read off a photo: capitals, no spaces. Compared whole, never guessed at. */
    static String normal(String code) {
        return code.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    /** Issued copies newest first, optionally of one code, member or sheet. */
    public Map<String, Object> adminList(String code, Long userId, Long activityId, int page, int pageSize) {
        List<String> where = new ArrayList<>();
        List<Object> filter = new ArrayList<>();
        if (code != null) {
            where.add("i.serial_code = ?");
            filter.add(normal(code));
        }
        if (userId != null) {
            where.add("i.user_id = ?");
            filter.add(userId);
        }
        if (activityId != null) {
            where.add("i.activity_id = ?");
            filter.add(activityId);
        }
        String condition = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        long count = jdbc.sql("select count(*) from worksheet_issuances i" + condition).params(filter).query(Long.class).single();
        List<Object> paged = new ArrayList<>(filter);
        paged.add(pageSize);
        paged.add((long) (page - 1) * pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        body.put("results", jdbc.sql(COLUMNS + ROWS + condition + " order by i.id desc limit ? offset ?").params(paged)
                .query((row, index) -> view(row, false)).list());
        return body;
    }

    /** One issued copy with its member's e-mail and every delivery of it. */
    public Map<String, Object> adminDetail(Long id) {
        Map<String, Object> body = jdbc.sql(COLUMNS + ROWS + " where i.id = ?").param(id)
                .query((row, index) -> view(row, true)).optional().orElseThrow(ApiException::notFound);
        body.put("downloads", jdbc.sql("select downloaded_at, ip_address, user_agent from pdf_downloads"
                        + " where issuance_id = ? order by id desc limit 100").param(id)
                .query((row, index) -> {
                    Map<String, Object> delivery = new LinkedHashMap<>();
                    delivery.put("downloaded_at", iso(row.getObject("downloaded_at", LocalDateTime.class)));
                    delivery.put("ip_address", row.getString("ip_address"));
                    delivery.put("user_agent", row.getString("user_agent"));
                    return delivery;
                }).list());
        return body;
    }
}
