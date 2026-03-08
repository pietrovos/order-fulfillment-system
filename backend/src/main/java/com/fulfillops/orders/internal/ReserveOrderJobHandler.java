package com.fulfillops.orders.internal;

import com.fulfillops.orders.OrderStatus;
import com.fulfillops.shared.jobs.Job;
import com.fulfillops.shared.jobs.JobHandler;
import java.util.EnumSet;
import org.springframework.stereotype.Component;

/**
 * Safety net for submission: the submit request reserves inline right after its transaction commits,
 * but if the process dies in between, this job (written in the same transaction as the order) finishes
 * the work. Idempotent because {@link ReservationService} re-checks status under a row lock.
 */
@Component
class ReserveOrderJobHandler implements JobHandler {

    private final ReservationService reservations;

    ReserveOrderJobHandler(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Override
    public String type() {
        return ReservationService.JOB_TYPE;
    }

    @Override
    public void handle(Job job) {
        reservations.reserve(Long.parseLong(job.aggregateId()), EnumSet.of(OrderStatus.SUBMITTED));
    }
}
