package com.fulfillops.shared.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-backed job poller.
 *
 * <ol>
 *   <li><b>Claim</b> (short transaction): {@code SELECT ... FOR UPDATE SKIP LOCKED} picks due jobs that
 *       no other node holds, stamps a lease and increments attempts, then commits. Several app
 *       instances can poll the same table without double-claiming.</li>
 *   <li><b>Run</b> (no transaction held by the runner): the handler opens its own transactions and may make
 *       slow external calls.</li>
 *   <li><b>Settle</b>: DONE, or back to PENDING with exponential backoff, or FAILED after max attempts.
 *       Settling is fenced on {@code locked_by} so a node whose lease expired cannot overwrite a newer claim.</li>
 * </ol>
 */
@Component
@EnableConfigurationProperties(JobProperties.class)
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final JobProperties props;
    private final Map<String, JobHandler> handlers;
    private final String nodeId = ManagementFactory.getRuntimeMXBean().getName();

    JobRunner(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, JobProperties props,
              List<JobHandler> handlers) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.props = props;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${fulfillops.jobs.poll-interval:PT0.5S}")
    void poll() {
        if (props.pollEnabled()) {
            runDue();
        }
    }

    /** Claims and runs one batch of due jobs. Returns how many were claimed. */
    public int runDue() {
        List<Job> claimed = claim();
        claimed.forEach(this::execute);
        return claimed.size();
    }

    /** Runs due jobs until none are left (jobs scheduled for a later retry are not waited for). */
    public int drain() {
        int total = 0;
        for (int i = 0; i < 100; i++) {
            int n = runDue();
            if (n == 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private List<Job> claim() {
        return tx.execute(s -> jdbc.query("""
                update outbox_jobs
                   set locked_until = now() + make_interval(secs => ?), locked_by = ?, attempts = attempts + 1
                 where id in (select id from outbox_jobs
                               where status = 'PENDING' and next_attempt_at <= now()
                                 and (locked_until is null or locked_until < now())
                               order by next_attempt_at, id
                               limit ?
                               for update skip locked)
                returning id, type, aggregate_id, payload::text, attempts, max_attempts
                """, (rs, i) -> {
            try {
                return new Job(rs.getLong("id"), rs.getString("type"), rs.getString("aggregate_id"),
                        json.readTree(rs.getString("payload")), rs.getInt("attempts"), rs.getInt("max_attempts"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, (double) props.lease().toSeconds(), nodeId, props.batchSize()));
    }

    private void execute(Job job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            // During a rolling deploy a newer node may enqueue a type this node cannot run yet. Defer it
            // without spending an attempt rather than dead-lettering it.
            log.warn("No handler for job type {} on this node; deferring job {}", job.type(), job.id());
            jdbc.update("""
                    update outbox_jobs set attempts = attempts - 1, locked_until = null,
                           next_attempt_at = now() + interval '60 seconds', last_error = ?
                    where id = ? and locked_by = ? and status = 'PENDING'
                    """, "No handler registered for " + job.type(), job.id(), nodeId);
            return;
        }
        try {
            handler.handle(job);
            jdbc.update("""
                    update outbox_jobs set status = 'DONE', completed_at = now(), locked_until = null, last_error = null
                    where id = ? and locked_by = ? and status = 'PENDING'
                    """, job.id(), nodeId);
        } catch (PermanentJobFailure e) {
            log.warn("Job {} ({} {}) failed permanently: {}", job.id(), job.type(), job.aggregateId(), e.getMessage());
            settleFailed(job, describe(e));
        } catch (Exception e) {
            if (job.attempts() >= job.maxAttempts()) {
                log.warn("Job {} ({} {}) exhausted {} attempts: {}", job.id(), job.type(), job.aggregateId(),
                        job.attempts(), describe(e));
                settleFailed(job, describe(e));
            } else {
                Duration delay = backoff(job.attempts());
                log.info("Job {} ({} {}) attempt {} failed, retrying in {} ms: {}", job.id(), job.type(),
                        job.aggregateId(), job.attempts(), delay.toMillis(), describe(e));
                jdbc.update("""
                        update outbox_jobs
                           set next_attempt_at = now() + make_interval(secs => ?), last_error = ?, locked_until = null
                         where id = ? and locked_by = ? and status = 'PENDING'
                        """, delay.toMillis() / 1000.0, describe(e), job.id(), nodeId);
            }
        }
    }

    private void settleFailed(Job job, String error) {
        jdbc.update("""
                update outbox_jobs set status = 'FAILED', last_error = ?, locked_until = null, completed_at = now()
                where id = ? and locked_by = ? and status = 'PENDING'
                """, error, job.id(), nodeId);
    }

    Duration backoff(int attempt) {
        long base = props.baseBackoff().toMillis() * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(base, props.maxBackoff().toMillis());
        long jitter = (long) (capped * 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        return Duration.ofMillis(Math.max(0, capped + jitter));
    }

    private static String describe(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
