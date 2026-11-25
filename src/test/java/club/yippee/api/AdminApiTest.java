package club.yippee.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n%%EOF".getBytes();

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
        byte[] newer = "%PDF-1.7\nsecond edition\n%%EOF".getBytes();
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
