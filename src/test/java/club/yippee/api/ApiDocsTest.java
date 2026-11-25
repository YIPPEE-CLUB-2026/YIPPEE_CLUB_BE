package club.yippee.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The Swagger page with SWAGGER_ENABLED on. That it is closed when off is in CommonApiTest. */
@TestPropertySource(properties = "SWAGGER_ENABLED=true")
class ApiDocsTest extends ApiTestBase {

    @Test
    void thePageAndItsDocumentOpenWithoutAToken() throws Exception {
        get("/swagger-ui/index.html", null).andExpect(status().isOk());
        get("/v3/api-docs", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.accessToken.scheme").value("bearer"));
    }

    // A stale token sent to an open address is refused with 401, so login and refresh must not carry the lock.
    @Test
    void theLockIsOnlyOnWhatTheServerProtects() throws Exception {
        get("/v3/api-docs", null)
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.security[0].accessToken").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/orders'].get.security[0].accessToken").exists())
                .andExpect(jsonPath("$.paths['/api/v1/activities/{id}/download'].post.security[0].accessToken").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/token/refresh'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/activities/{id}'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/payments/webhook'].post.security").doesNotExist());
    }

    // A list's query fields come as one box that may stay empty. An activity's text fields travel in the
    // form beside its files: a description is too long for the address line.
    @Test
    void queryFieldsAreOptionalAndTheActivityFormCarriesItsOwnFields() throws Exception {
        String form = "$.paths['/api/v1/admin/activities'].post";
        get("/v3/api-docs", null)
                .andExpect(jsonPath("$.paths['/api/v1/orders'].get.parameters[0].required").value(false))
                .andExpect(jsonPath(form + ".parameters").doesNotExist())
                // An empty example: otherwise the page fills each box with the word "string" and sends it.
                .andExpect(jsonPath(form + ".requestBody.content['multipart/form-data'].schema.properties.title.example").value(""))
                .andExpect(jsonPath(form + ".requestBody.content['multipart/form-data'].schema.properties.rental_price.example").value(""))
                .andExpect(jsonPath(form + ".requestBody.content['multipart/form-data'].schema.properties.pdf").exists())
                .andExpect(jsonPath(form + ".requestBody.content['multipart/form-data'].schema.required",
                        org.hamcrest.Matchers.hasItems("image", "pdf", "title", "price", "categories")))
                .andExpect(jsonPath(form + ".requestBody.content['multipart/form-data'].schema.required",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItems("rental_price", "is_public"))));
    }

    @Test
    void whatTheServerFillsInItselfIsNotOfferedAsAField() throws Exception {
        get("/v3/api-docs", null)
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/activities/{id}/files/{kind}'].get.parameters[?(@.name == 'method')]").isEmpty());
    }
}
