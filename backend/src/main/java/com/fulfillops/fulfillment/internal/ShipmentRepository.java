package com.fulfillops.fulfillment.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findByOrderId(long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id")
    Optional<Shipment> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select s from Shipment s
            where (:status is null or s.status = :status)
              and (:q = '' or lower(s.orderNumber) like lower(concat('%', :q, '%'))
                           or lower(s.shipToName) like lower(concat('%', :q, '%'))
                           or lower(coalesce(s.trackingNumber, '')) like lower(concat('%', :q, '%')))
            order by s.createdAt desc
            """)
    List<Shipment> search(@Param("status") ShipmentStatus status, @Param("q") String q, Pageable pageable);
}
