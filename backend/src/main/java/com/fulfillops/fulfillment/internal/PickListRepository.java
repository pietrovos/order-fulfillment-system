package com.fulfillops.fulfillment.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PickListRepository extends JpaRepository<PickList, Long> {

    Optional<PickList> findByOrderId(long orderId);

    List<PickList> findByStatusOrderByStartedAt(PickList.Status status);
}
