package com.fulfillops.orders.internal;

import com.fulfillops.orders.OrderStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * SELECT ... FOR UPDATE on the order row. Every status transition goes through this, so two
     * concurrent actions on one order (cancel vs. pick, retry vs. cancel) are serialised and the
     * second one evaluates the state machine against the first one's committed result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") long id);

    @Query("""
            select o from Order o
            where (:statusesEmpty = true or o.status in :statuses)
              and (:q = '' or lower(o.orderNumber) like lower(concat('%', :q, '%'))
                           or lower(o.customerName) like lower(concat('%', :q, '%')))
            """)
    Page<Order> search(@Param("q") String q, @Param("statuses") Collection<OrderStatus> statuses,
                       @Param("statusesEmpty") boolean statusesEmpty, Pageable pageable);

    @Query("select o.status as status, count(o) as total from Order o group by o.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        OrderStatus getStatus();

        long getTotal();
    }
}
