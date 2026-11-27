package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Registering activities and setting prices, which only the accounts in ADMIN_EMAILS may do. */
class AdminApiTest extends ApiTestBase {

    private static final String ADMIN = "/api/v1/admin";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0};
    private static final byte[] PDF = samplePdf(1);

    private MockMultipartHttpServletRequestBuilder upload(Session who, byte[] image, byte[] pdf, String price, String categories) {
        MockMultipartHttpServletRequestBuilder builder = multipart(ADMIN + "/activities");
        if (image != null) {
            builder.file(new MockMultipartFile("image", "../cover.png", "image/png", image));
        }
        builder.file(new MockMultipartFile("pdf", "play.pdf", "application/pdf", pdf));
        builder.param("title", "새 활동지").param("description", "설명").param("age_min_months", "60")
                .param("age_max_months", "65").param("difficulty", "easy").param("duration_min", "5")
                .param("duration_max", "10").param("is_public", "true").param("categories", categories);
        if (price != null) {
            builder.param("price", price);
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.access());
        }
        return builder;
    }

    private MockMultipartHttpServletRequestBuilder previews(Session who, long id, byte[]... pages) {
        MockMultipartHttpServletRequestBuilder builder = multipart(ADMIN + "/activities/" + id + "/previews");
        for (byte[] page : pages) {
            builder.file(new MockMultipartFile("previews", "page.png", "image/png", page));
        }
        return (MockMultipartHttpServletRequestBuilder) builder.header("Authorization", "Bearer " + who.access());
    }

    // Sample pages shown before buying: an admin uploads up to five images, anyone may look at
    // them while the activity is on the catalogue.
    @Test
    void anAdminUploadsSamplePagesThatAnyoneMaySeeBeforeBuying() throws Exception {
        Session admin = signUp("admin@example.com");
        Session member = signUp("member@example.com");
        Number category = read(post(ADMIN + "/categories", admin.access(), q("{'name':'자연탐구'}")), "$.id");
        Number created = read(mvc.perform(upload(admin, PNG, PDF, "1500", category.toString())), "$.id");
        long id = created.longValue();
        String preview = "/api/v1/activities/" + id + "/preview";
        byte[] second = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 1, 1, 1, 1};

        get(preview, null).andExpect(status().isOk()).andExpect(jsonPath("$.preview_urls.length()").value(0));
        expectError(mvc.perform(previews(member, id, PNG)), 403, "PERMISSION_DENIED");
        mvc.perform(previews(admin, id, PNG, second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.preview_urls.length()").value(2));

        ResultActions listed = get(preview, null).andExpect(jsonPath("$.preview_urls.length()").value(2));
        String first = ((String) read(listed, "$.preview_urls[0]")).replace("http://localhost", "");
        String other = ((String) read(listed, "$.preview_urls[1]")).replace("http://localhost", "");
        assertThat(first).startsWith("/api/v1/activities/" + id + "/previews/1");
        get(first, null).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        get(other, null).andExpect(status().isOk()).andExpect(content().bytes(second));

        // A new upload replaces the whole set; a bad file or too many change nothing.
        mvc.perform(previews(admin, id, second)).andExpect(status().isOk());
        get(first, null).andExpect(content().bytes(second));
        expectError(get("/api/v1/activities/" + id + "/previews/2", null), 404, "NOT_FOUND");
        expectError(mvc.perform(previews(admin, id, "not an image".getBytes())), 400, "VALIDATION_ERROR");
        expectError(mvc.perform(previews(admin, id, PNG, PNG, PNG, PNG, PNG, PNG)), 400, "VALIDATION_ERROR");
        expectError(mvc.perform(previews(admin, id)), 400, "VALIDATION_ERROR");
        expectError(mvc.perform(previews(admin, 999999, PNG)), 404, "NOT_FOUND");
        get(preview, null).andExpect(jsonPath("$.preview_urls.length()").value(1));
        for (String odd : new String[]{"0", "6", "x", "99999999999999999999"}) {
            expectError(get("/api/v1/activities/" + id + "/previews/" + odd, null), 404, "NOT_FOUND");
        }

        // Off the catalogue, the samples are off too. Taking them away leaves an empty list.
        patch(ADMIN + "/activities/" + id, admin.access(), q("{'is_public':false}")).andExpect(status().isOk());
        expectError(get(preview, null), 404, "NOT_FOUND");
        expectError(get(first, null), 404, "NOT_FOUND");
        patch(ADMIN + "/activities/" + id, admin.access(), q("{'is_public':true}")).andExpect(status().isOk());
        expectError(delete(ADMIN + "/activities/" + id + "/previews", member.access()), 403, "PERMISSION_DENIED");
        delete(ADMIN + "/activities/" + id + "/previews", admin.access()).andExpect(status().isOk());
        get(preview, null).andExpect(jsonPath("$.preview_urls.length()").value(0));
    }

    // The admin's own list and detail: an activity that is off the catalogue can be found, read and put back.
    @Test
    void anAdminFindsAndReadsActivitiesThatAreOffTheCatalogue() throws Exception {
        Session admin = signUp("admin@example.com");
        Session member = signUp("member@example.com");
        Number nature = read(post(ADMIN + "/categories", admin.access(), q("{'name':'자연탐구'}")), "$.id");
        Number art = read(post(ADMIN + "/categories", admin.access(), q("{'name':'미술'}")), "$.id");
        long shown = ((Number) read(mvc.perform(upload(admin, PNG, PDF, "1500", nature.toString())), "$.id")).longValue();
        long hidden = ((Number) read(mvc.perform(upload(admin, PNG, PDF, "0", art.toString())), "$.id")).longValue();
        patch(ADMIN + "/activities/" + hidden, admin.access(), q("{'is_public':false,'title':'숨긴 활동지','difficulty':'normal'}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.difficulty").value("normal"));
        mvc.perform(previews(admin, hidden, PNG)).andExpect(status().isOk());
        String list = ADMIN + "/activities";

        // Newest first, the hidden one included. The public catalogue still shows only the other.
        get(list, admin.access()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.results[0].id").value(hidden)).andExpect(jsonPath("$.results[0].is_public").value(false))
                .andExpect(jsonPath("$.results[1].id").value(shown)).andExpect(jsonPath("$.results[1].is_public").value(true));
        get("/api/v1/activities", null).andExpect(jsonPath("$.count").value(1));
        get(list + "?is_public=false", admin.access()).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].id").value(hidden));
        get(list + "?is_public=true", admin.access()).andExpect(jsonPath("$.results[0].id").value(shown));
        get(list + "?q=숨긴", admin.access()).andExpect(jsonPath("$.count").value(1));
        get(list + "?category=" + nature, admin.access()).andExpect(jsonPath("$.results[0].id").value(shown));
        get(list + "?page=2&page_size=1", admin.access()).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.results.length()").value(1)).andExpect(jsonPath("$.results[0].id").value(shown));
        for (String bad : new String[]{"?is_public=maybe", "?page=0", "?page_size=101", "?category=x", "?q=", "?sort=title"}) {
            expectError(get(list + bad, admin.access()), 400, "VALIDATION_ERROR");
        }
        expectError(get(list, member.access()), 403, "PERMISSION_DENIED");
        expectError(get(list, null), 401, "AUTHENTICATION_REQUIRED");

        // The detail carries every stored field, and links to the files that work for an admin whatever the state.
        ResultActions detail = get(list + "/" + hidden, admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("숨긴 활동지")).andExpect(jsonPath("$.is_public").value(false))
                .andExpect(jsonPath("$.difficulty").value("normal")).andExpect(jsonPath("$.price").value(0))
                .andExpect(jsonPath("$.categories[0]").value(art.intValue()))
                .andExpect(jsonPath("$.created_at").isString()).andExpect(jsonPath("$.updated_at").isString())
                .andExpect(jsonPath("$.admin_preview_urls.length()").value(1));
        String image = ((String) read(detail, "$.admin_image")).replace("http://localhost", "");
        String pdf = ((String) read(detail, "$.admin_pdf")).replace("http://localhost", "");
        String sample = ((String) read(detail, "$.admin_preview_urls[0]")).replace("http://localhost", "");
        get(image, admin.access()).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        get(pdf, admin.access()).andExpect(status().isOk()).andExpect(content().bytes(PDF))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        get(sample, admin.access()).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        // Nobody else gets them, and the public addresses of a hidden activity stay shut.
        for (String address : new String[]{image, pdf, sample, list + "/" + hidden}) {
            expectError(get(address, member.access()), 403, "PERMISSION_DENIED");
            expectError(get(address, null), 401, "AUTHENTICATION_REQUIRED");
        }
        expectError(get("/api/v1/activities/" + hidden + "/files/image", null), 404, "NOT_FOUND");
        expectError(get("/api/v1/activities/" + hidden + "/preview", null), 404, "NOT_FOUND");
        expectError(get(list + "/999999", admin.access()), 404, "NOT_FOUND");
        expectError(get(list + "/" + hidden + "/files/other", admin.access()), 404, "NOT_FOUND");
        expectError(get(list + "/" + hidden + "/previews/2", admin.access()), 404, "NOT_FOUND");

        // Put back on the catalogue, the customer sees it, with the middle difficulty searchable.
        patch(ADMIN + "/activities/" + hidden, admin.access(), q("{'is_public':true}")).andExpect(status().isOk());
        get("/api/v1/activities?difficulty=normal", null).andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].id").value(hidden));
        expectError(patch(ADMIN + "/activities/" + hidden, admin.access(), q("{'difficulty':'medium'}")), 400, "VALIDATION_ERROR");
    }

    @Test
    void onlyAnAdminRegistersActivitiesAndSetsPrices() throws Exception {
        Session admin = signUp("admin@example.com");
        Session member = signUp("member@example.com");
        expectError(post(ADMIN + "/categories", member.access(), q("{'name':'x'}")), 403, "PERMISSION_DENIED");
        expectError(post(ADMIN + "/categories", null, q("{'name':'x'}")), 401, "AUTHENTICATION_REQUIRED");
        expectError(mvc.perform(upload(member, PNG, PDF, "1500", "1")), 403, "PERMISSION_DENIED");
        expectError(patch(ADMIN + "/activities/1", member.access(), q("{'price':0}")), 403, "PERMISSION_DENIED");

        Number category = read(post(ADMIN + "/categories", admin.access(), q("{'name':'자연탐구'}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("자연탐구")), "$.id");
        ResultActions created = mvc.perform(upload(admin, PNG, PDF, "1500", category.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.price").value(1500))
                .andExpect(jsonPath("$.is_public").value(true)).andExpect(jsonPath("$.categories[0]").value(category));
        Number id = read(created, "$.id");
        String address = "/api/v1/activities/" + id;

        get(address, null).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("새 활동지"))
                .andExpect(jsonPath("$.price").value(1500));
        get(address + "/files/image", null).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        // It has a price, so its PDF is closed to the public.
        expectError(get(address + "/files/pdf", null), 404, "NOT_FOUND");
        // A price left out is not "free": a free sheet's PDF is open to everyone, so 0 must be typed.
        expectError(mvc.perform(upload(admin, PNG, PDF, null, category.toString())), 400, "VALIDATION_ERROR");
        mvc.perform(upload(admin, PNG, PDF, "0", category.toString())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(0));

        // A 7-day price is more than nothing and less than the price for good, whichever of the two
        // is being changed. An explicit null takes the 7-day pass away again.
        String priced = ADMIN + "/activities/" + id;
        get(address, null).andExpect(jsonPath("$.rental_price").isEmpty());
        patch(priced, admin.access(), q("{'rental_price':500}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rental_price").value(500)).andExpect(jsonPath("$.price").value(1500));
        for (String bad : new String[]{"{'rental_price':1500}", "{'rental_price':0}", "{'rental_price':'500'}",
                "{'price':500}", "{'price':0}", "{'price':400,'rental_price':400}"}) {
            expectError(patch(priced, admin.access(), q(bad)), 400, "VALIDATION_ERROR");
        }
        patch(priced, admin.access(), q("{'price':900,'rental_price':300}")).andExpect(jsonPath("$.rental_price").value(300));
        get(address, null).andExpect(jsonPath("$.rental_price").value(300)).andExpect(jsonPath("$.price").value(900));
        patch(priced, admin.access(), q("{'rental_price':null}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rental_price").isEmpty());
        expectError(mvc.perform(upload(admin, PNG, PDF, "1500", category.toString()).param("rental_price", "1500")),
                400, "VALIDATION_ERROR");
        mvc.perform(upload(admin, PNG, PDF, "1500", category.toString()).param("rental_price", "700"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rental_price").value(700));

        patch(ADMIN + "/activities/" + id, admin.access(), q("{'price':0,'title':'무료가 된 활동지'}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(0));
        get(address + "/files/pdf", null).andExpect(status().isOk()).andExpect(content().bytes(PDF));

        // Replacing the PDF: the same address then serves the new file, and a bad file changes nothing.
        byte[] newer = samplePdf(2);
        mvc.perform(multipart(ADMIN + "/activities/" + id + "/files")
                .file(new MockMultipartFile("pdf", "v2.pdf", "application/pdf", newer))
                .header("Authorization", "Bearer " + admin.access())).andExpect(status().isOk());
        get(address + "/files/pdf", null).andExpect(content().bytes(newer));
        expectError(mvc.perform(multipart(ADMIN + "/activities/" + id + "/files")
                .file(new MockMultipartFile("pdf", "bad.pdf", "application/pdf", "x".getBytes()))
                .header("Authorization", "Bearer " + admin.access())), 400, "VALIDATION_ERROR");
        get(address + "/files/pdf", null).andExpect(content().bytes(newer));
        for (String bad : new String[]{"{'price':-1}", "{'price':'0'}", "{'age_max_months':1}", "{'difficulty':'medium'}",
                "{'categories':[999999]}", "{'categories':[]}", "{'is_public':'yes'}", "{'pdf_path':'x'}"}) {
            expectError(patch(ADMIN + "/activities/" + id, admin.access(), q(bad)), 400, "VALIDATION_ERROR");
        }
        patch(ADMIN + "/activities/" + id, admin.access(), q("{'is_public':false}")).andExpect(jsonPath("$.is_public").value(false));
        expectError(get(address, null), 404, "NOT_FOUND");
        expectError(patch(ADMIN + "/activities/999999", admin.access(), q("{'price':0}")), 404, "NOT_FOUND");
    }

    @Test
    void anUploadMustBeARealImageAndPdfWithSaneNumbers() throws Exception {
        Session admin = signUp("admin@example.com");
        Number category = read(post(ADMIN + "/categories", admin.access(), q("{'name':'c'}")), "$.id");
        String ids = category.toString();
        expectError(mvc.perform(upload(admin, PNG, "not a pdf".getBytes(), "0", ids)), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.details.pdf").isArray());
        expectError(mvc.perform(upload(admin, "not an image!".getBytes(), PDF, "0", ids)), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.details.image").isArray());
        expectError(mvc.perform(upload(admin, null, PDF, "0", ids)), 400, "VALIDATION_ERROR");
        expectError(mvc.perform(upload(admin, PNG, PDF, "-5", ids)), 400, "VALIDATION_ERROR");
        expectError(mvc.perform(upload(admin, PNG, PDF, "0", "999999")), 400, "VALIDATION_ERROR");
        expectError(mvc.perform(upload(admin, PNG, PDF, "0", "")), 400, "VALIDATION_ERROR");
        get("/api/v1/activities", null).andExpect(jsonPath("$.count").value(0));
    }
}
