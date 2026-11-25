package club.yippee.api.account;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * One emailed code and what became of it: sent, then verified (which mints a proof token),
 * then consumed by exactly one signup.
 */
@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    String id;
    String email;
    String purpose = "signup";
    String codeDigest;
    int attempts;
    Instant expiresAt;
    Instant verifiedAt;
    String tokenDigest;
    Instant tokenExpiresAt;
    Instant consumedAt;
    Instant createdAt = Instant.now();

    protected EmailVerification() {
    }

    EmailVerification(String id, String email, String codeDigest, Instant expiresAt) {
        this.id = id;
        this.email = email;
        this.codeDigest = codeDigest;
        this.expiresAt = expiresAt;
    }
}

interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.id = :id")
    Optional<EmailVerification> lockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.tokenDigest = :digest")
    Optional<EmailVerification> lockByTokenDigest(@Param("digest") String digest);

    @Modifying
    @Query("delete from EmailVerification v where v.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);
}
