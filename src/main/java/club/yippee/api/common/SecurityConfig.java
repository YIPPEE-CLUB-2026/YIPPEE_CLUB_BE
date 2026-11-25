package club.yippee.api.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /** The token-version claim: a password change raises the user's number and old tokens stop matching. */
    public static final String VERSION_CLAIM = "ver";

    /** Signed-in users only, although it sits under the open /activities path. */
    static final String DOWNLOAD = "/api/v1/activities/*/download";

    // Public by path, not by method, so a wrong method gets its 405 from the router.
    // The payment company calls the webhook without a token; nothing in its body is trusted.
    static final String[] OPEN = {"/api/v1/health/", "/api/v1/health/ready", "/api/v1/auth/**", "/api/v1/activity-categories",
            "/api/v1/activities", "/api/v1/activities/**", "/api/v1/payments/webhook"};

    private static final String[] SWAGGER = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"};

    /** Whether an address answers without a token. The Swagger page reads this to put its lock where the server has one. */
    static boolean open(String path) {
        AntPathMatcher matcher = new AntPathMatcher();
        return !matcher.match(DOWNLOAD, path) && Arrays.stream(OPEN).anyMatch(pattern -> matcher.match(pattern, path));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ErrorHandler errors, JdbcClient jdbc, AppProperties properties,
                                    @Value("${springdoc.api-docs.enabled:false}") boolean swagger) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // Listed first: the first rule that matches decides.
                    requests.requestMatchers(DOWNLOAD).authenticated()
                            // Registering activities and setting prices: only the accounts named in ADMIN_EMAILS.
                            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                            .requestMatchers(OPEN).permitAll();
                    if (swagger) {
                        requests.requestMatchers(SWAGGER).permitAll();
                    }
                    // Anything new is closed until it is listed above.
                    requests.anyRequest().authenticated();
                })
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                token -> authenticate(token, jdbc, properties.adminEmails())))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }

    // A signed token is not enough: the user must still be active and the token no older than
    // the last password change. One indexed read per request.
    private static AbstractAuthenticationToken authenticate(Jwt token, JdbcClient jdbc, List<String> adminEmails) {
        long userId;
        try {
            userId = Long.parseLong(token.getSubject());
        } catch (NumberFormatException error) {
            throw new InvalidBearerTokenException("subject");
        }
        Map<String, Object> user = jdbc.sql("select token_version, email from users where id = ? and is_active = true")
                .param(userId).query().listOfRows().stream().findFirst().orElse(null);
        Object claimed = token.getClaim(VERSION_CLAIM);
        if (user == null || !(claimed instanceof Number number)
                || number.intValue() != ((Number) user.get("token_version")).intValue()) {
            throw new InvalidBearerTokenException("stale");
        }
        // Read on every request: taking an address off ADMIN_EMAILS takes the role away at the next restart.
        // An account that still has a sign-in door opened on an unproven address (see social_accounts)
        // may be somebody else's, so it is no admin until a password reset has removed that door.
        boolean admin = adminEmails.contains(String.valueOf(user.get("email")))
                && jdbc.sql("select count(*) from social_accounts where user_id = ? and email_verified = false")
                .param(userId).query(Long.class).single() == 0;
        return new UsernamePasswordAuthenticationToken(userId, null,
                admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of());
    }

    @Bean
    SecretKey signingKey(AppProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey signingKey) {
        return NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.corsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Without this a browser on the frontend's origin cannot read the request id.
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
