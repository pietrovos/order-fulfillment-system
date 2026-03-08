package com.fulfillops.shared.jobs;

/**
 * Handles one job type. Delivery is at-least-once (a crash after the handler's work commits but
 * before the job is marked done re-runs it), so every handler must be idempotent.
 * Throwing schedules a retry with backoff; throw {@link PermanentJobFailure} to dead-letter immediately.
 */
public interface JobHandler {

    String type();

    void handle(Job job) throws Exception;
}
