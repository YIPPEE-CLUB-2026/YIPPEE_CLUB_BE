package club.yippee.api.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * One purchase. pending until the payment is settled; then paid or payment_failed.
 * A paid order becomes refunded when the payment company reports a full refund.
 */
@Entity
@Table(name = "orders")
public class Order {

    static final String PENDING = "pending";
    static final String PAID = "paid";
    static final String FAILED = "payment_failed";
    static final String CANCELLED = "cancelled";
    static final String REFUNDED = "refunded";
    /** Money moved in a way we did not ask for. Nothing was granted; a person must resolve it. */
    static final String REVIEW = "review";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    Long userId;
    String status = PENDING;
    /** Won. Summed from the stored prices when the order was made, never taken from a request. */
    int amount;
    /** The order number the payment company knows this order by. */
    String pgOrderId;
    String pgPaymentKey;
    String receiptUrl;
    /** Set when a confirm call went out. pending with this set means "the outcome is not known yet". */
    Instant confirmStartedAt;
    Instant paidAt;
    /** When the money went back. The sales chart counts the refund at this moment. */
    Instant refundedAt;
    /** When the buyer agreed to the refund notice. Every order that costs money has it. */
    Instant refundNoticeAgreedAt;
    Instant createdAt = Instant.now();
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @BatchSize(size = 100)
    List<Item> items = new ArrayList<>();

    /** What was bought, with the title and price of that day, and whether for good or for 7 days. */
    @Embeddable
    public record Item(Long activityId, String title, int price, String plan) {
    }

    protected Order() {
    }

    Order(Long userId, String pgOrderId) {
        this.userId = userId;
        this.pgOrderId = pgOrderId;
    }
}

interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    Optional<Order> findByPgOrderId(String pgOrderId);

    Page<Order> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Page<Order> findByUserIdAndCreatedAtGreaterThanEqualOrderByIdDesc(Long userId, Instant since, Pageable pageable);

    Page<Order> findByStatusOrderByIdDesc(String status, Pageable pageable);

    Page<Order> findAllByOrderByIdDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.userId = :userId and o.status = 'pending'")
    List<Order> lockPendingOf(@Param("userId") Long userId);

    @Query("select o.id from Order o where o.status = 'pending' and o.confirmStartedAt < :before order by o.id")
    List<Long> unsettledBefore(@Param("before") Instant before, Pageable limit);

    @Modifying
    @Query("update Order o set o.status = 'cancelled'"
            + " where o.status = 'pending' and o.confirmStartedAt is null and o.createdAt < :before")
    int cancelAbandoned(@Param("before") Instant before);
}
