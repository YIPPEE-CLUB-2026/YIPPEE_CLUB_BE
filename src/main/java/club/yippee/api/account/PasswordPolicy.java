package club.yippee.api.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Component;

/**
 * What a new password must be: 8 or more characters with a letter and a digit (the spec), and,
 * as the Django server also required, not a common password and not close to the email.
 */
@Component
public class PasswordPolicy {

    /** bcrypt reads no further than 72 bytes, so a longer password would be silently cut. */
    static final int MAX_BYTES = 72;
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final double TOO_SIMILAR = 0.7;

    private final Set<String> common = new HashSet<>();

    PasswordPolicy() throws IOException {
        // Django's list of about 20,000 common passwords (django.contrib.auth, BSD licence).
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                PasswordPolicy.class.getResourceAsStream("/common-passwords.txt.gz")), StandardCharsets.UTF_8))) {
            reader.lines().map(String::strip).forEach(common::add);
        }
    }

    public boolean accepts(String password, String email) {
        return problem(password, email) == null;
    }

    /** Null when the password is acceptable; otherwise why not, in words the app can show the user. */
    public String problem(String password, String email) {
        if (password.codePointCount(0, password.length()) < 8
                || !LETTER.matcher(password).find() || !DIGIT.matcher(password).find()) {
            return "영문과 숫자를 포함해 8자 이상 입력해 주세요.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "비밀번호가 너무 깁니다.";
        }
        if (common.contains(password.toLowerCase(Locale.ROOT).strip())) {
            return "너무 흔한 비밀번호입니다.";
        }
        if (resembles(password.toLowerCase(Locale.ROOT), email.toLowerCase(Locale.ROOT))) {
            return "이메일과 비슷한 비밀번호는 사용할 수 없습니다.";
        }
        return null;
    }

    // Django's UserAttributeSimilarityValidator for the one attribute a user has here:
    // the email, whole and split at punctuation.
    private static boolean resembles(String password, String email) {
        List<String> parts = new ArrayList<>(List.of(email.split("\\W+")));
        parts.add(email);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            boolean farTooShort = password.length() >= 10 * part.length()
                    && part.length() < TOO_SIMILAR / 2 * password.length();
            if (!farTooShort && sharedLetterRatio(password, part) >= TOO_SIMILAR) {
                return true;
            }
        }
        return false;
    }

    // difflib.SequenceMatcher.quick_ratio: letters in common, counted with repeats, over total length.
    private static double sharedLetterRatio(String left, String right) {
        Map<Integer, Integer> available = new HashMap<>();
        right.chars().forEach(letter -> available.merge(letter, 1, Integer::sum));
        int shared = 0;
        for (int letter : left.chars().toArray()) {
            if (available.merge(letter, -1, Integer::sum) >= 0) {
                shared++;
            }
        }
        return 2.0 * shared / (left.length() + right.length());
    }
}
