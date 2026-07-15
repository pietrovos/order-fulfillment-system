package com.fulfillops.fulfillment.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the carrier's booking API. Requests reuse the shipment's idempotency key so retries
 * return the same booking, including when the carrier booked it but the response never arrived.
 */
@Component
@EnableConfigurationProperties(CarrierProperties.class)
class CarrierClient {

    record Booking(String shipmentId, String trackingNumber, boolean replayed) {
    }

    /** Worth retrying: network failure, timeout, lost response, 5xx, 429. */
    static class TransientCarrierException extends RuntimeException {
        TransientCarrierException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Retrying the same request will not help (4xx validation error, etc.). */
    static class RejectedByCarrierException extends RuntimeException {
        RejectedByCarrierException(String message) {
            super(message);
        }
    }

    private final CarrierProperties props;
    private final ObjectMapper json;
    private final HttpClient http;

    CarrierClient(CarrierProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    Booking book(String idempotencyKey, Map<String, Object> request) {
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/shipments"))
                    .timeout(props.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Timeouts and connection resets leave the booking outcome unknown.
            // Retrying with the same idempotency key returns any existing booking.
            throw new TransientCarrierException("No response from carrier: " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientCarrierException("Interrupted while calling carrier", e);
        }
        int status = res.statusCode();
        if (status == 200 || status == 201) {
            try {
                JsonNode body = json.readTree(res.body());
                return new Booking(body.get("shipmentId").asText(), body.get("trackingNumber").asText(),
                        "true".equals(res.headers().firstValue("Idempotent-Replayed").orElse(null)));
            } catch (IOException | NullPointerException e) {
                throw new TransientCarrierException("Unreadable carrier response", e);
            }
        }
        if (status >= 500 || status == 429 || status == 408) {
            throw new TransientCarrierException("Carrier returned HTTP " + status, null);
        }
        throw new RejectedByCarrierException("Carrier rejected booking: HTTP " + status + " " + res.body());
    }

    private static String describe(IOException e) {
        String type = e.getClass().getSimpleName();
        return e.getMessage() == null ? type : type + " (" + e.getMessage() + ")";
    }
}
