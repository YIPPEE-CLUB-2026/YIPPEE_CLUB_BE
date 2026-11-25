package club.yippee.api.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/** Codes and tokens are never stored: the database keeps only their keyed digest. */
@Component
public class Digests {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] secret;

    Digests(AppProperties properties) {
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC-SHA256 of the value, hex. The namespace keeps one kind of secret from standing in for another. */
    public String of(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((namespace + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Comparison that takes the same time wherever the two differ. */
    public static boolean same(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public static String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    public static String randomHex(int bytes) {
        return HexFormat.of().formatHex(randomBytes(bytes));
    }

    public static String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
