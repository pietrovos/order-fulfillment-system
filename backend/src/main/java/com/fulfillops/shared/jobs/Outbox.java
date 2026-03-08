package com.fulfillops.shared.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox. {@link #enqueue} must run inside the business transaction: the job row
 * commits if and only if the business change commits.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(String type, String aggregateId, Map<String, ?> payload) {
        try {
            return jdbc.queryForObject("""
                    insert into outbox_jobs (type, aggregate_id, payload) values (?, ?, ?::jsonb) returning id
                    """, Long.class, type, aggregateId, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserialisable job payload", e);
        }
    }

    /** Marks pending jobs done when the work was already performed inline in this transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeInline(String type, String aggregateId) {
        jdbc.update("""
                update outbox_jobs set status = 'DONE', completed_at = now(), locked_until = null, last_error = null
                where type = ? and aggregate_id = ? and status = 'PENDING'
                """, type, aggregateId);
    }
}
