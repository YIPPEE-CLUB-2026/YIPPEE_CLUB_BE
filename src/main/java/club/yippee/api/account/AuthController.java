package club.yippee.api.account;

import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import club.yippee.api.common.ApiException;
import club.yippee.api.common.AppProperties;
import club.yippee.api.common.Fields;
import club.yippee.api.common.RateLimiter;

/**
 * The public sign-in endpoints. Each one counts the caller's address before reading the body,
 * then the email once the body is known to be valid.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final Map<String, Object> DONE = Map.of("message", "처리되었습니다.");
    private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{6}");
    private static ApiException alreadyRegistered() {
        return new ApiException(409, "ALREADY_EXISTS", "이미 가입한 이메일입니다.");
    }

    private final AccountService accounts;
    private final VerificationService verification;
    private final PasswordPolicy policy;
    private final RateLimiter limiter;
    private final JsonMapper json;
    private final AppProperties.Auth auth;

    AuthController(AccountService accounts, VerificationService verification, PasswordPolicy policy,
                   RateLimiter limiter, JsonMapper json, AppProperties properties) {
        this.accounts = accounts;
        this.verification = verification;
        this.policy = policy;
        this.limiter = limiter;
        this.json = json;
        this.auth = properties.auth();
        this.requiredAgreements = properties.requiredAgreements();
    }

    private final java.util.List<String> requiredAgreements;

    @PostMapping("/identifiers/check")
    Map<String, Object> check(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "identifier-check");
        Fields fields = Fields.lenient(body);
        String email = fields.email("identifier");
        fields.check();
        limiter.identity("identifier-check", email);
        return Map.of("registered", accounts.registered(email), "identifier_type", "email");
    }

    @PostMapping("/verification/send")
    Map<String, Object> send(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "verification-send");
        Fields fields = Fields.lenient(body);
        String email = fields.email("identifier");
        if (!"signup".equals(fields.raw("purpose"))) {
            fields.reject("purpose");
        }
        fields.check();
        limiter.identity("verification-send", email);
        if (accounts.registered(email)) {
            throw alreadyRegistered();
        }
        // Counted and committed before the mail is tried: a failing mail server cannot be used to skip the wait.
        limiter.consume("send:cooldown", email, 1, auth.sendCooldownSeconds());
        EmailVerification challenge = verification.send(email);
        return Map.of("verification_id", challenge.id, "expires_in_seconds", auth.codeTtlSeconds());
    }

    @PostMapping("/verification/confirm")
    Map<String, Object> confirm(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "verification-confirm");
        Fields fields = Fields.lenient(body);
        String id = fields.text("verification_id", 80, false);
        String code = fields.string("code");
        if (code != null && !SIX_DIGITS.matcher(code).matches()) {
            fields.reject("code");
        }
        fields.check();
        VerificationService.Outcome outcome = verification.confirm(id, code);
        if (outcome.error() != null) {
            throw outcome.error();
        }
        return Map.of("verification_token", outcome.proofToken(), "verified", true);
    }

    @PostMapping("/signup")
    ResponseEntity<Map<String, Object>> signup(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "signup");
        Fields fields = Fields.strict(body, "identifier", "verification_token", "password", "agreements");
        String email = fields.email("identifier");
        String proof = fields.string("verification_token");
        String password = fields.string("password");
        String agreements = null;
        if (fields.raw("agreements") instanceof Map<?, ?> consent) {
            agreements = json.writeValueAsString(consent);
            // Each consent the service requires (REQUIRED_AGREEMENTS) must be given as true.
            for (String required : requiredAgreements) {
                if (!Boolean.TRUE.equals(consent.get(required))) {
                    fields.reject("agreements");
                }
            }
        }
        if (agreements == null || agreements.length() > 4000) {
            fields.reject("agreements");
        }
        fields.check();
        String problem = policy.problem(password, email);
        if (problem != null) {
            throw ApiException.reason("password", problem);
        }
        limiter.identity("signup", email);
        try {
            return ResponseEntity.status(201).body(accounts.signup(email, proof, password, agreements));
        } catch (DataIntegrityViolationException sameEmailJustCreated) {
            // Two signups for one email at once: the unique index let one through.
            throw alreadyRegistered();
        }
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "login");
        Fields fields = Fields.strict(body, "email", "password");
        String email = fields.email("email");
        String password = fields.string("password");
        fields.check();
        limiter.identityFrom(request, "login", email);
        return accounts.login(email, password);
    }

    @PostMapping("/token/refresh")
    Map<String, Object> refresh(@RequestBody(required = false) Object body) {
        // No per-address cap here: every signed-in client calls this every few minutes, and many
        // clients share one address (a carrier, a daycare's Wi-Fi). Without a valid 256-bit token
        // the call does nothing but one indexed lookup.
        Fields fields = Fields.strict(body, "refresh_token");
        String token = fields.string("refresh_token");
        fields.check();
        return accounts.refresh(token);
    }

    @PostMapping("/logout")
    Map<String, Object> logout(@RequestBody(required = false) Object body) {
        Fields fields = Fields.strict(body == null ? Map.of() : body, "refresh_token");
        // The token is optional: left out or null, the call succeeds and ends nothing.
        String token = fields.has("refresh_token") && !fields.isNull("refresh_token")
                ? fields.string("refresh_token") : null;
        fields.check();
        accounts.logout(token);
        return DONE;
    }

    @PostMapping("/password/reset-request")
    Map<String, Object> resetRequest(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "password-reset-request");
        Fields fields = Fields.strict(body, "email");
        String email = fields.email("email");
        fields.check();
        limiter.identity("password-reset-request", email);
        accounts.requestPasswordReset(email);
        return DONE;
    }

    @PostMapping("/password/reset-confirm")
    Map<String, Object> resetConfirm(@RequestBody(required = false) Object body, HttpServletRequest request) {
        limiter.address(request, "password-reset-confirm");
        Fields fields = Fields.strict(body, "reset_token", "new_password");
        String token = fields.string("reset_token");
        String password = fields.string("new_password");
        fields.check();
        accounts.confirmPasswordReset(token, password);
        return DONE;
    }
}
