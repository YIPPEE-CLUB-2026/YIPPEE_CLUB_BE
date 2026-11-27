package club.yippee.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import club.yippee.api.order.PaymentGateway.Payment;
import club.yippee.api.order.PaymentGateway.State;

/** The code printed on each member's copy of a sheet: who gets one, what it says, and who may look it up. */
class IssuanceApiTest extends ApiTestBase {

    private static final String CODE = "W\\d{4,}(-[2-9A-HJ-NP-Z]{4}){3}";
    private static final String ISSUANCES = "/api/v1/admin/worksheet-issuances";
    private static final Path ORIGINAL = Path.of("build/test-files/activities/issued-play.pdf");
    private Session buyer;
    private Session other;
    private long sheet;

    @BeforeEach
    void accountsAndOnePaidSheetOfTwoPages() throws Exception {
        buyer = signUp("buyer@example.com");
        other = signUp("other@example.com");
        Files.createDirectories(ORIGINAL.getParent());
        // The second page is stored turned on its side, as a landscape page often is.
        try (PDDocument document = Loader.loadPDF(samplePdf(2))) {
            document.getPage(1).setRotation(90);
            document.save(ORIGINAL.toFile());
        }
        sheet = activity(1900, "activities/issued-play.pdf");
    }

    private long activity(int price, String pdfPath) {
        jdbc.sql("insert into activities (title, description, age_min_months, age_max_months, difficulty, duration_min,"
                        + " duration_max, price, image_path, pdf_path, is_public, created_at, updated_at)"
                        + " values ('코드 활동지', '설명', 60, 65, 'easy', 5, 5, ?, 'activities/cover.png', ?, true, ?, ?)")
                .params(price, pdfPath, utc(Instant.now()), utc(Instant.now())).update();
        return count("select max(id) from activities");
    }

    /** Buys the sheet and returns the order's id. */
    private long pay(Session who, String paymentKey) throws Exception {
        ResultActions created = post("/api/v1/orders", who.access(), "{\"activity_ids\":[" + sheet
                + "],\"agreed_to_payment\":true,\"agreed_to_refund_notice\":true}").andExpect(status().isCreated());
        Number id = read(created, "$.order_id");
        String number = read(created, "$.payment_session_token");
        when(gateway.confirm(eq(paymentKey), anyString(), anyLong())).thenReturn(new Payment(State.DONE, 1900, paymentKey, null, "DONE"));
        when(gateway.lookup(number)).thenReturn(new Payment(State.DONE, 1900, paymentKey, null, "DONE"));
        post("/api/v1/payments/confirm", who.access(), q("{'order_id':%s,'pg_payment_key':'%s','pg_order_id':'%s'}"
                .formatted(id, paymentKey, number))).andExpect(status().isOk());
        return id.longValue();
    }

    private ResultActions download(Session who, long activity) throws Exception {
        return post("/api/v1/activities/" + activity + "/download", who.access(), null);
    }

    private static String link(ResultActions issued) throws Exception {
        return ((String) read(issued, "$.download_url")).replace("http://localhost", "");
    }

    @Test
    void theBuyersCopyCarriesARandomCodeOnEveryPageAndTheOriginalIsUntouched() throws Exception {
        long order = pay(buyer, "pk_1");
        byte[] original = Files.readAllBytes(ORIGINAL);

        ResultActions issued = download(buyer, sheet).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.issuance_code").value(org.hamcrest.Matchers.matchesPattern(CODE)))
                .andExpect(jsonPath("$.expires_at").isString());
        String code = read(issued, "$.issuance_code");
        assertThat((String) read(issued, "$.file_name")).isEqualTo(code + ".pdf");
        // The code is the sheet's number and twelve random characters: nothing of the member is in it.
        assertThat(code).startsWith("W%04d-".formatted(sheet));

        String link = link(issued);
        byte[] copy = get(link, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + code + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(copy)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            PDFTextStripper reader = new PDFTextStripper();
            for (int page = 1; page <= 2; page++) {
                reader.setStartPage(page);
                reader.setEndPage(page);
                assertThat(reader.getText(document)).contains("CODE: " + code).doesNotContain("buyer@example.com");
            }
            // The code sits in the bottom left corner as the reader sees the page, 6 mm from both
            // edges, on the upright page and on the one stored turned alike.
            java.util.List<float[]> starts = new java.util.ArrayList<>();
            PDFTextStripper where = new PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> glyphs) {
                    org.apache.pdfbox.text.TextPosition first = glyphs.get(0);
                    // Measured up from the bottom of the page as it is seen: a turned page is as tall as it is stored wide.
                    float seenHeight = first.getRotation() % 180 == 0 ? first.getPageHeight() : first.getPageWidth();
                    starts.add(new float[]{first.getXDirAdj(), seenHeight - first.getYDirAdj()});
                }
            };
            where.getText(document);
            assertThat(starts).hasSize(2);
            for (float[] start : starts) {
                assertThat(start[0]).isCloseTo(17f, org.assertj.core.data.Offset.offset(1f));
                assertThat(start[1]).isCloseTo(17f, org.assertj.core.data.Offset.offset(1f));
            }
            // Nothing in the file's own description names the member either.
            assertThat(document.getDocumentInformation().getAuthor()).isNull();
        }
        assertThat(Files.readAllBytes(ORIGINAL)).isEqualTo(original);

        // The link names the issued copy, not the member: decoding it shows no user number.
        String token = link.substring(link.indexOf("token=") + 6);
        String said = new String(Base64.getUrlDecoder().decode(token.substring(0, token.lastIndexOf('.'))), StandardCharsets.UTF_8);
        long issuance = count("select id from worksheet_issuances where serial_code = ?", code);
        assertThat(said).startsWith(issuance + ":").doesNotContain(":" + buyer.userId() + ":");
        assertThat(said.split(":")).hasSize(2);

        // What is stored ties the code to the member, the sheet, the order and the moment.
        assertThat(count("select count(*) from worksheet_issuances where serial_code = ? and user_id = ? and activity_id = ?"
                + " and order_id = ? and issued_at is not null", code, buyer.userId(), sheet, order)).isEqualTo(1);
        assertThat(count("select count(*) from pdf_downloads where issuance_id = ?", issuance)).isEqualTo(1);

        // Asking again prints the same code. Another buyer gets another.
        download(buyer, sheet).andExpect(jsonPath("$.issuance_code").value(code));
        pay(other, "pk_2");
        String theirs = read(download(other, sheet).andExpect(status().isOk()), "$.issuance_code");
        assertThat(theirs).isNotEqualTo(code).matches(CODE);
        assertThat(count("select count(*) from worksheet_issuances")).isEqualTo(2);
    }

    @Test
    void theCodeStaysTheSameAfterARefundAndABuyBack() throws Exception {
        Session admin = signUp("admin@example.com");
        long order = pay(buyer, "pk_1");
        ResultActions issued = download(buyer, sheet);
        String code = read(issued, "$.issuance_code");
        String link = link(issued);

        String number = jdbc.sql("select pg_order_id from orders where id = ?").param(order).query(String.class).single();
        when(gateway.cancel(eq("pk_1"), anyString())).thenReturn(Payment.of(State.CANCELED, "CANCELED"));
        when(gateway.lookup(number)).thenReturn(new Payment(State.CANCELED, 1900, "pk_1", null, "CANCELED"));
        post("/api/v1/admin/orders/" + order + "/refund", admin.access(), q("{'reason':'환불'}")).andExpect(status().isOk());
        // After the refund the old link is dead and no new one is made.
        expectError(get(link, null), 404, "NOT_FOUND");
        expectError(download(buyer, sheet), 403, "PERMISSION_DENIED");
        get(ISSUANCES + "?code=" + code, admin.access()).andExpect(jsonPath("$.results[0].right").value("revoked"));

        pay(buyer, "pk_3");
        download(buyer, sheet).andExpect(status().isOk()).andExpect(jsonPath("$.issuance_code").value(code));
        assertThat(count("select count(*) from worksheet_issuances")).isEqualTo(1);
    }

    @Test
    void aFreeSheetTakenThroughALinkIsCodedToo() throws Exception {
        long free = activity(0, "activities/issued-play.pdf");
        String code = read(download(other, free).andExpect(status().isOk()), "$.issuance_code");
        ResultActions again = download(other, free).andExpect(jsonPath("$.issuance_code").value(code));
        assertThat(count("select count(*) from worksheet_issuances where user_id = ? and activity_id = ? and order_id is null",
                other.userId(), free)).isEqualTo(1);
        byte[] copy = get(link(again), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(copy)) {
            assertThat(new PDFTextStripper().getText(document)).contains(code);
        }
    }

    @Test
    void aFileThatCannotBeCodedIsNeverSentInItsPlace() throws Exception {
        Files.write(Path.of("build/test-files/activities/broken.pdf"), "%PDF-1.4\n%%EOF".getBytes());
        long broken = activity(0, "activities/broken.pdf");
        expectError(download(buyer, broken), 500, "PDF_GENERATION_FAILED");
    }

    @Test
    void onlyAnAdminLooksACodeUp() throws Exception {
        Session admin = signUp("admin@example.com");
        long order = pay(buyer, "pk_1");
        ResultActions issued = download(buyer, sheet);
        String code = read(issued, "$.issuance_code");
        get(link(issued), null).andExpect(status().isOk());
        pay(other, "pk_2");
        download(other, sheet).andExpect(status().isOk());

        // Knowing a code opens nothing: there is no address a member can hand one to.
        expectError(get(ISSUANCES + "?code=" + code, other.access()), 403, "PERMISSION_DENIED");
        expectError(get(ISSUANCES + "?code=" + code, null), 401, "AUTHENTICATION_REQUIRED");
        long id = count("select id from worksheet_issuances where serial_code = ?", code);
        expectError(get(ISSUANCES + "/" + id, other.access()), 403, "PERMISSION_DENIED");

        get(ISSUANCES, admin.access()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.results[0].user_id").value(other.userId()));
        // A code read off a photo: lower case and spaces are forgiven, a near miss is not guessed at.
        get(ISSUANCES + "?code= " + code.toLowerCase() + " ", admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].id").value(id))
                .andExpect(jsonPath("$.results[0].issuance_code").value(code))
                .andExpect(jsonPath("$.results[0].user_id").value(buyer.userId()))
                .andExpect(jsonPath("$.results[0].activity_id").value(sheet))
                .andExpect(jsonPath("$.results[0].activity_title").value("코드 활동지"))
                .andExpect(jsonPath("$.results[0].order_id").value(order))
                .andExpect(jsonPath("$.results[0].issued_at").isString())
                .andExpect(jsonPath("$.results[0].right").value("active"))
                .andExpect(jsonPath("$.results[0].download_count").value(1))
                .andExpect(jsonPath("$.results[0].email").doesNotExist());
        get(ISSUANCES + "?code=" + code.substring(0, code.length() - 1), admin.access()).andExpect(jsonPath("$.count").value(0));
        get(ISSUANCES + "?user_id=" + other.userId() + "&activity_id=" + sheet, admin.access())
                .andExpect(jsonPath("$.count").value(1));

        get(ISSUANCES + "/" + id, admin.access()).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuance_code").value(code))
                .andExpect(jsonPath("$.email").value("buyer@example.com"))
                .andExpect(jsonPath("$.downloads.length()").value(1))
                .andExpect(jsonPath("$.downloads[0].downloaded_at").isString())
                .andExpect(jsonPath("$.downloads[0].ip_address").value("127.0.0.1"));
        expectError(get(ISSUANCES + "/999999", admin.access()), 404, "NOT_FOUND");
        for (String query : new String[]{"?page=0", "?page_size=101", "?user_id=x", "?code=", "?unknown=1"}) {
            expectError(get(ISSUANCES + query, admin.access()), 400, "VALIDATION_ERROR");
        }
    }
}
