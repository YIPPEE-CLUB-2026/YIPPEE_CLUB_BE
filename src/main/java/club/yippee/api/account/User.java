package club.yippee.api.account;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    /** Always trimmed and lower-cased before it gets here. */
    String email;
    String passwordHash;
    String name = "";
    String nickname = "";
    String phone = "";
    /** Who the member is to the child: mother, father, grandparent or other. Null until told. */
    String relation;
    /** The member's own age group: 20s, 30s, 40s or 50s_plus. Null when not told; it is optional. */
    String ageGroup;
    /** The consent object the client sent at signup, as JSON text. */
    String agreements;
    Long activeChildId;
    /** Raised on every password change; access tokens carry the number they were issued under. */
    int tokenVersion;
    @Column(name = "is_active")
    boolean active = true;
    Instant lastLoginAt;
    Instant createdAt = Instant.now();
    /** Set when the member left. Such a row is never opened again. */
    Instant withdrawnAt;

    protected User() {
    }

    User(String email, String passwordHash, String agreements) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.agreements = agreements;
    }
}

interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> lockByEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockById(@Param("id") Long id);
}
