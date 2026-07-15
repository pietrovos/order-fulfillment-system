package com.fulfillops.fulfillment.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fulfillops.carrier")
record CarrierProperties(
        @DefaultValue("http://localhost:8091") String baseUrl,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout) {
}
