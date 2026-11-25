package club.yippee.api.account;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** One login on one device. Only the digest of its refresh token is kept. */
@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    Long userId;
    String tokenDigest;
    Instant expiresAt;
    Instant revokedAt;
    Instant createdAt = Instant.now();

    protected RefreshSession() {
    }

    RefreshSession(Long userId, String tokenDigest, Instant expiresAt) {
        this.userId = userId;
        this.tokenDigest = tokenDigest;
        this.expiresAt = expiresAt;
    }
}

interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenDigest = :digest")
    Optional<RefreshSession> lockByTokenDigest(@Param("digest") String digest);

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAll(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshSession s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
