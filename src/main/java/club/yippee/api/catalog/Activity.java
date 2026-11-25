package club.yippee.api.catalog;

import java.time.Instant;
import java.util.Collection;
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
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** One activity sheet: its catalogue entry, its cover image and its PDF. */
@Entity
@Table(name = "activities")
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String title;
    String description;
    int ageMinMonths;
    int ageMaxMonths;
    /** "easy" or "hard". */
    String difficulty;
    int durationMin;
    int durationMax;
    /** Won, whole numbers. */
    int price;
    /** Won for 7 days of access; null when the sheet is sold for good only. Always below the price. */
    Integer rentalPrice;
    String imagePath;
    String pdfPath;
    @Column(name = "is_public")
    boolean published;
    Instant createdAt = Instant.now();
    Instant updatedAt = Instant.now();
    // Loaded for a whole page of activities in one extra query, not one query each.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "activity_categories", joinColumns = @JoinColumn(name = "activity_id"))
    @Column(name = "category_id")
    @BatchSize(size = 100)
    Set<Long> categoryIds = new TreeSet<>();

    protected Activity() {
    }

    public Long id() {
        return id;
    }

    public int price() {
        return price;
    }

    public Integer rentalPrice() {
        return rentalPrice;
    }

    public String title() {
        return title;
    }

    public boolean published() {
        return published;
    }
}

interface ActivityRepository extends JpaRepository<Activity, Long>, JpaSpecificationExecutor<Activity> {

    Optional<Activity> findByIdAndPublishedTrue(Long id);

    List<Activity> findByIdInAndPublishedTrue(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.id = :id")
    Optional<Activity> lockAny(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Activity a where a.id = :id and a.published = true")
    Optional<Activity> lockPublic(@Param("id") Long id);
}
