package club.yippee.api.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.Fields;

/**
 * Registering activities and setting prices. Open only to the accounts named in ADMIN_EMAILS
 * (the security rules check that before any method here runs). Not part of the 99-API spec:
 * it replaces what Django's admin pages did.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    static final int MAX_PDF_BYTES = 20 * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_PRICE = 10_000_000;
    private static final int MAX_MONTHS = 1200;
    private static final int MAX_MINUTES = 65535;

    private final Activities activities;
    private final JdbcClient jdbc;

    AdminController(Activities activities, JdbcClient jdbc) {
        this.activities = activities;
        this.jdbc = jdbc;
    }

    @PostMapping("/categories")
    ResponseEntity<Map<String, Object>> category(@RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "name");
        String name = fields.text("name", 100, false);
        fields.check();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("insert into categories (name) values (?)").param(name).update(key, "id");
        return ResponseEntity.status(201).body(Map.of("id", key.getKey().longValue(), "name", name));
    }

    /** A form upload: the fields below plus the files "image" and "pdf". */
    @PostMapping(value = "/activities", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> create(@RequestParam Map<String, String> form,
                                               @RequestParam(required = false) MultipartFile image,
                                               @RequestParam(required = false) MultipartFile pdf) throws IOException {
        Set<String> faults = new LinkedHashSet<>();
        String title = text(form, "title", 255, faults);
        String description = text(form, "description", 4000, faults);
        Integer ageMin = number(form, "age_min_months", 0, MAX_MONTHS, faults);
        Integer ageMax = number(form, "age_max_months", 0, MAX_MONTHS, faults);
        String difficulty = form.get("difficulty");
        if (!"easy".equals(difficulty) && !"hard".equals(difficulty)) {
            faults.add("difficulty");
        }
        Integer durationMin = number(form, "duration_min", 1, MAX_MINUTES, faults);
        Integer durationMax = number(form, "duration_max", 1, MAX_MINUTES, faults);
        // Required: a price left out would mean 0, and a free sheet's PDF is open to everyone.
        Integer price = number(form, "price", 0, MAX_PRICE, faults);
        // Optional: the price of a 7-day pass. Left out, the sheet is sold for good only.
        Integer rental = form.containsKey("rental_price") ? number(form, "rental_price", 1, MAX_PRICE, faults)
                : Integer.valueOf(0);
        String visible = form.getOrDefault("is_public", "false");
        if (!visible.equals("true") && !visible.equals("false")) {
            faults.add("is_public");
        }
        List<Long> categories = new ArrayList<>();
        try {
            for (String part : form.getOrDefault("categories", "").split(",")) {
                categories.add(Long.valueOf(part.strip()));
            }
        } catch (NumberFormatException error) {
            faults.add("categories");
        }
        if (new HashSet<>(categories).size() != categories.size()) {
            faults.add("categories");
        }
        byte[] imageBytes = image == null || image.getSize() > MAX_IMAGE_BYTES ? null : image.getBytes();
        String imageExtension = imageExtension(imageBytes);
        if (imageExtension == null) {
            faults.add("image");
        }
        byte[] pdfBytes = pdf == null || pdf.getSize() > MAX_PDF_BYTES ? null : pdf.getBytes();
        if (!looksLikePdf(pdfBytes)) {
            faults.add("pdf");
        }
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        return ResponseEntity.status(201).body(activities.register(new Activities.Draft(title, description, ageMin, ageMax,
                difficulty, durationMin, durationMax, price, rental, visible.equals("true"), categories),
                imageBytes, imageExtension, pdfBytes));
    }

    /**
     * A form upload of one to five "previews" images: the sample pages shown before buying.
     * They replace whatever was there, in the order sent.
     */
    @PostMapping(value = "/activities/{id:[0-9]{1,18}}/previews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> replacePreviews(@PathVariable Long id,
                                        @RequestParam(required = false) List<MultipartFile> previews) throws IOException {
        List<byte[]> images = new java.util.ArrayList<>();
        List<String> extensions = new java.util.ArrayList<>();
        boolean sound = previews != null && !previews.isEmpty() && previews.size() <= Activities.MAX_PREVIEWS;
        for (MultipartFile page : sound ? previews : List.<MultipartFile>of()) {
            byte[] bytes = page.getSize() > MAX_IMAGE_BYTES ? null : page.getBytes();
            String extension = imageExtension(bytes);
            sound &= extension != null;
            images.add(bytes);
            extensions.add(extension);
        }
        if (!sound) {
            throw ApiException.field("previews");
        }
        return activities.replacePreviews(id, images, extensions);
    }

    /** Takes the activity's sample pages away. */
    @org.springframework.web.bind.annotation.DeleteMapping("/activities/{id:[0-9]{1,18}}/previews")
    Map<String, Object> removePreviews(@PathVariable Long id) throws IOException {
        return activities.replacePreviews(id, List.of(), List.of());
    }

    /** A form upload with "image", "pdf" or both: replaces that file of an existing activity. */
    @PostMapping(value = "/activities/{id:[0-9]+}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> replaceFiles(@PathVariable String id,
                                     @RequestParam(required = false) MultipartFile image,
                                     @RequestParam(required = false) MultipartFile pdf) throws IOException {
        Set<String> faults = new LinkedHashSet<>();
        byte[] imageBytes = image == null || image.getSize() > MAX_IMAGE_BYTES ? null : image.getBytes();
        String imageExtension = imageExtension(imageBytes);
        if (image != null && imageExtension == null) {
            faults.add("image");
        }
        byte[] pdfBytes = pdf == null || pdf.getSize() > MAX_PDF_BYTES ? null : pdf.getBytes();
        if (pdf != null && !looksLikePdf(pdfBytes)) {
            faults.add("pdf");
        }
        if (image == null && pdf == null) {
            faults.add("image");
            faults.add("pdf");
        }
        if (!faults.isEmpty()) {
            throw ApiException.fields(faults);
        }
        if (id.length() > 18) {
            throw ApiException.notFound();
        }
        return activities.replaceFiles(Long.valueOf(id), imageBytes, imageExtension, pdfBytes);
    }

    /** JSON with any of the fields; what is left out stays as it is. Files have their own address above. */
    @PatchMapping("/activities/{id:[0-9]+}")
    Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body, "title", "description", "age_min_months", "age_max_months", "difficulty",
                "duration_min", "duration_max", "price", "rental_price", "is_public", "categories");
        String title = fields.has("title") ? fields.text("title", 255, false) : null;
        String description = fields.has("description") ? fields.text("description", 4000, false) : null;
        Integer ageMin = fields.has("age_min_months") ? fields.whole("age_min_months", 0, MAX_MONTHS) : null;
        Integer ageMax = fields.has("age_max_months") ? fields.whole("age_max_months", 0, MAX_MONTHS) : null;
        String difficulty = fields.has("difficulty") ? fields.string("difficulty") : null;
        if (difficulty != null && !difficulty.equals("easy") && !difficulty.equals("hard")) {
            fields.reject("difficulty");
        }
        Integer durationMin = fields.has("duration_min") ? fields.whole("duration_min", 1, MAX_MINUTES) : null;
        Integer durationMax = fields.has("duration_max") ? fields.whole("duration_max", 1, MAX_MINUTES) : null;
        Integer price = fields.has("price") ? fields.whole("price", 0, MAX_PRICE) : null;
        // A number sets the 7-day price; an explicit null takes the 7-day pass away.
        Integer rental = !fields.has("rental_price") ? null
                : fields.isNull("rental_price") ? Integer.valueOf(0) : fields.whole("rental_price", 1, MAX_PRICE);
        Boolean visible = null;
        if (fields.has("is_public")) {
            if (fields.raw("is_public") instanceof Boolean flag) {
                visible = flag;
            } else {
                fields.reject("is_public");
            }
        }
        List<Long> categories = null;
        if (fields.has("categories")) {
            categories = new ArrayList<>();
            if (fields.raw("categories") instanceof List<?> items) {
                for (Object item : items) {
                    Long category = Fields.asId(item);
                    if (category == null || categories.contains(category)) {
                        fields.reject("categories");
                        break;
                    }
                    categories.add(category);
                }
            } else {
                fields.reject("categories");
            }
        }
        fields.check();
        if (id.length() > 18) {
            throw ApiException.notFound();
        }
        return activities.revise(Long.valueOf(id), new Activities.Draft(title, description, ageMin, ageMax, difficulty,
                durationMin, durationMax, price, rental, visible, categories));
    }

    private static String text(Map<String, String> form, String name, int max, Set<String> faults) {
        String value = form.getOrDefault(name, "").strip();
        if (value.isEmpty() || value.length() > max) {
            faults.add(name);
        }
        return value;
    }

    // A required whole number from a form field.
    private static Integer number(Map<String, String> form, String name, int min, int max, Set<String> faults) {
        Integer value = CatalogController.whole(form, name, min, max, faults);
        if (value == null) {
            faults.add(name);
        }
        return value;
    }

    // shortcut: the file's opening bytes are checked, not its whole structure, and nothing scans
    // for malicious content. Enough while only our own admins upload; revisit before anyone else may.
    static String imageExtension(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if (starts(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }
        if (starts(bytes, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        if (starts(bytes, 'R', 'I', 'F', 'F') && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "webp";
        }
        return null;
    }

    static boolean looksLikePdf(byte[] bytes) {
        if (bytes == null || !starts(bytes, '%', 'P', 'D', 'F', '-')) {
            return false;
        }
        byte[] tail = Arrays.copyOfRange(bytes, Math.max(0, bytes.length - 1024), bytes.length);
        return new String(tail, StandardCharsets.ISO_8859_1).contains("%%EOF");
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xFF) != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
