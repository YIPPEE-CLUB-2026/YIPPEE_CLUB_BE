package club.yippee.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.json.JsonCompareMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class CommonApiTest extends ApiTestBase {

    @Test
    void healthIsPublicAndTouchesNoData() throws Exception {
        get("/api/v1/health/", null).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ok\"}", JsonCompareMode.STRICT));
    }

    @Test
    void onlyTheFrontendsOriginMayCallFromABrowser() throws Exception {
        for (String[] origin : new String[][]{{"http://localhost:3000", "200"}, {"https://elsewhere.example", "403"}}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/login")
                            .header("Origin", origin[0]).header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().is(Integer.parseInt(origin[1])));
        }
    }

    @Test
    void everyResponseCarriesTheRequestIdThatAnErrorBodyRepeats() throws Exception {
        get("/api/v1/activities", null).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-Request-Id"));
        ResultActions refused = get("/api/v1/users/me", null);
        expectError(refused, 401, "AUTHENTICATION_REQUIRED");
        String id = read(refused, "$.request_id");
        assertThat(refused.andReturn().getResponse().getHeader("X-Request-Id")).isEqualTo(id);
    }

    @Test
    void readinessAsksTheDatabase() throws Exception {
        get("/api/v1/health/ready", null).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ok\"}", JsonCompareMode.STRICT));
    }

    @Test
    void healthRejectsPostInTheErrorShape() throws Exception {
        expectError(post("/api/v1/health/", null, "{}"), 405, "INVALID_STATE");
    }

    // Off unless SWAGGER_ENABLED says so: closed to a visitor, and not there at all for a signed-in user.
    @Test
    void theSwaggerPageIsNotServedUnlessTurnedOn() throws Exception {
        String access = signUp("docs@example.com").access();
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui/index.html"}) {
            expectError(get(path, null), 401, "AUTHENTICATION_REQUIRED");
            expectError(get(path, access), 404, "NOT_FOUND");
        }
    }

    @Test
    void protectedPathWithoutTokenIs401() throws Exception {
        expectError(get("/api/v1/users/me", null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void badTokenIs401EvenOnAPublicPath() throws Exception {
        expectError(get("/api/v1/health/", "not-a-token"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void trailingSlashIsADifferentAddress() throws Exception {
        expectError(post("/api/v1/auth/login/", null, "{}"), 404, "NOT_FOUND");
        // A sign-in provider whose keys are not set is not there at all, and neither is one we do not offer.
        expectError(post("/api/v1/auth/social/kakao", null, "{\"authorization_code\":\"c\"}"), 404, "NOT_FOUND");
        expectError(post("/api/v1/auth/social/apple", null, "{\"authorization_code\":\"c\"}"), 404, "NOT_FOUND");
    }

    @Test
    void anUnexpectedErrorIsHiddenFromTheClientAndLoggedWithItsRequestId(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("boom-db-down")).when(accounts).login(anyString(), anyString());
        ResultActions failed = post("/api/v1/auth/login", null, q("{'email':'a@example.com','password':'x'}"));
        expectError(failed, 500, "INTERNAL_SERVER_ERROR");
        String requestId = read(failed, "$.request_id");
        assertThat(failed.andReturn().getResponse().getContentAsString()).doesNotContain("boom-db-down");
        assertThat(output.getOut()).contains(requestId).contains("boom-db-down");
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsParsed() throws Exception {
        String huge = "{\"email\":\"a@example.com\",\"password\":\"" + "x".repeat(70_000) + "\"}";
        expectError(post("/api/v1/auth/login", null, huge), 413, "VALIDATION_ERROR");
    }
}
