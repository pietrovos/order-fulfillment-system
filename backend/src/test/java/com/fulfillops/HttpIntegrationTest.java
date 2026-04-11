package com.fulfillops;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Real HTTP against an embedded Tomcat on a random port: concurrent requests are handled by separate
 * server threads with separate DB connections, exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fulfillops.jobs.poll-enabled=false")
@Import(TestcontainersConfiguration.class)
public abstract class HttpIntegrationTest {

    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;

    @Autowired
    protected com.fasterxml.jackson.databind.ObjectMapper json;

    protected RestClient http() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(factory)
                // let tests inspect 4xx/5xx bodies instead of throwing
                .defaultStatusHandler(s -> true, (req, res) -> { })
                .build();
    }

    protected String bearer(String user) {
        return "Bearer " + TOKENS.computeIfAbsent(user, u -> http().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", u, "password", "fulfill123"))
                .retrieve().body(JsonNode.class).get("token").asText());
    }

    protected long createProduct(String sku, int onHand) {
        JsonNode p = http().post().uri("/api/products").header("Authorization", bearer("supervisor"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sku", sku, "name", "Race item " + sku, "unitPrice", "5.00"))
                .retrieve().body(JsonNode.class);
        long id = p.get("id").asLong();
        if (onHand > 0) {
            http().post().uri("/api/inventory/stock/{id}/receipts", id).header("Authorization", bearer("warehouse"))
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("quantity", onHand))
                    .retrieve().toBodilessEntity();
        }
        return id;
    }

    /** Submits an order with a fresh idempotency key and returns the response body (order or error). */
    protected JsonNode submit(String customer, Map<Long, Integer> lines) {
        var body = Map.of("customerName", customer, "shippingAddress", "1 Test Way",
                "lines", lines.entrySet().stream()
                        .map(e -> Map.of("productId", e.getKey(), "quantity", e.getValue())).toList());
        return http().post().uri("/api/orders?submit=true").header("Authorization", bearer("sales"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
    }

    protected JsonNode cancel(long orderId) {
        return http().post().uri("/api/orders/{id}/cancel", orderId).header("Authorization", bearer("sales"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "test"))
                .retrieve().body(JsonNode.class);
    }

    protected static String uniqueSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
