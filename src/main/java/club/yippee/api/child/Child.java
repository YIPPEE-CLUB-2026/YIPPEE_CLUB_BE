package club.yippee.api.child;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** A child of one user, with the categories the child is interested in. */
@Entity
@Table(name = "children")
public class Child {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    Long userId;
    String name;
    LocalDate birthDate;
    String gender = "";
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "child_interests", joinColumns = @JoinColumn(name = "child_id"))
    @Column(name = "category_id")
    @BatchSize(size = 100)
    Set<Long> interests = new TreeSet<>();

    protected Child() {
    }

    Child(Long userId) {
        this.userId = userId;
    }
}

interface ChildRepository extends JpaRepository<Child, Long> {

    List<Child> findByUserIdOrderById(Long userId);

    Optional<Child> findByIdAndUserId(Long id, Long userId);

    // Ownership is part of the query: another user's child is simply not found.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.id = :id and c.userId = :userId")
    Optional<Child> lockOwned(@Param("id") Long id, @Param("userId") Long userId);
}
