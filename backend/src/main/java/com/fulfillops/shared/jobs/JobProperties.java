package com.fulfillops.shared.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pollEnabled  run the scheduled poller (tests turn it off and drive {@link JobRunner} directly)
 * @param lease        how long a claimed job is invisible to other pollers; must exceed the slowest handler
 * @param baseBackoff  first retry delay; doubles per attempt up to {@code maxBackoff}
 */
@ConfigurationProperties("fulfillops.jobs")
public record JobProperties(
        @DefaultValue("true") boolean pollEnabled,
        @DefaultValue("10") int batchSize,
        @DefaultValue("PT60S") Duration lease,
        @DefaultValue("PT1S") Duration baseBackoff,
        @DefaultValue("PT5M") Duration maxBackoff) {
}
