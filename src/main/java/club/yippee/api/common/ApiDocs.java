package club.yippee.api.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Swagger page: its heading, and the Authorize box that takes an access token. */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class ApiDocs {

    private static final String TOKEN = "accessToken";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("YIPPEE CLUB API").version("v1").description(
                        "요청과 응답의 필드는 저장소의 docs/API_SPEC.md에 있습니다. 이 화면은 주소를 눌러 보는 용도이고, 본문은 JSON을 직접 적습니다.\n\n"
                                + "로그인이 필요한 API: `POST /api/v1/auth/login`(본문 `{\"email\": \"...\", \"password\": \"...\"}`)의 응답에서 `access_token` 값을 복사해 Authorize에 붙여 넣습니다. "
                                + "Access 토큰은 5분 뒤 만료되므로 401이 나오면 다시 로그인해 새 값을 넣습니다."))
                .components(new Components().addSecuritySchemes(TOKEN,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    // The text fields AdminController.create reads from its form: name, hint, and "*" where the server demands it.
    private static final String[][] ACTIVITY_FIELDS = {{"title", "", "*"}, {"description", "", "*"},
            {"age_min_months", "개월", "*"}, {"age_max_months", "개월", "*"}, {"difficulty", "easy, normal 또는 hard", "*"},
            {"duration_min", "분", "*"}, {"duration_max", "분", "*"}, {"price", "원. 무료는 0", "*"},
            {"rental_price", "7일 이용권 가격. 비우면 영구 소장만 판매", ""}, {"is_public", "true 또는 false. 비우면 false", ""},
            {"categories", "카테고리 번호를 쉼표로 구분. 예: 1,2", "*"}};

    @Bean
    OpenApiCustomizer shape() {
        return api -> {
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                // The lock goes only where the server has one. An open address refuses a stale token with 401,
                // so a lock on login or refresh would break them as soon as the pasted token ran out.
                if (!SecurityConfig.open(path)) {
                    operation.addSecurityItem(new SecurityRequirement().addList(TOKEN));
                }
                // A method that takes every query field as one Map shows as one box; it may stay empty.
                if (operation.getParameters() != null) {
                    operation.getParameters().stream().filter(ApiDocs::isFieldMap).forEach(field -> field.setRequired(false));
                }
            }));
            // An activity's text fields go in the form beside its files, not on the address line:
            // a description is longer than an address may be.
            Operation create = api.getPaths().get("/api/v1/admin/activities").getPost();
            create.getParameters().removeIf(ApiDocs::isFieldMap);
            if (create.getParameters().isEmpty()) {
                create.setParameters(null);
            }
            Schema<?> form = create.getRequestBody().getContent().get("multipart/form-data").getSchema();
            form.addRequiredItem("image").addRequiredItem("pdf");
            for (String[] field : ACTIVITY_FIELDS) {
                // example(""): without it the page fills every box with the word "string" and sends it.
                // An empty box is left out of the request, which is what the server reads as "not given".
                form.addProperty(field[0], new StringSchema().example("").description(field[1].isEmpty() ? null : field[1]));
                if (!field[2].isEmpty()) {
                    form.addRequiredItem(field[0]);
                }
            }
        };
    }

    private static boolean isFieldMap(Parameter parameter) {
        return "query".equals(parameter.getIn()) && parameter.getSchema() != null
                && parameter.getSchema().getAdditionalProperties() != null;
    }
}
