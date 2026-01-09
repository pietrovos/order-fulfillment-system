package com.fulfillops.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret base64-encoded HMAC key, at least 256 bits
 * @param ttl    token lifetime
 */
@ConfigurationProperties("fulfillops.jwt")
public record JwtProperties(String secret, Duration ttl, String issuer) {
}
