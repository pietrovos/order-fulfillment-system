package com.fulfillops.shared.jobs;

import com.fasterxml.jackson.databind.JsonNode;

/** A claimed unit of background work. {@code attempts} already counts the current attempt. */
public record Job(long id, String type, String aggregateId, JsonNode payload, int attempts, int maxAttempts) {
}
