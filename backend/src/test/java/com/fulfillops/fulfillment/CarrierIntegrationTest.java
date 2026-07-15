package com.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fulfillops.IntegrationTest;
import com.fulfillops.shared.jobs.JobRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Shipment booking against the real carrier simulator, built from carrier-sim/Dockerfile and run in a
 * container. Faults are injected through the simulator's admin API.
 */
@TestPropertySource(properties = {
        "fulfillops.carrier.read-timeout=PT1S",
        "fulfillops.jobs.base-backoff=PT0.05S",
        "fulfillops.jobs.max-backoff=PT0.4S"})
class CarrierIntegrationTest extends IntegrationTest {

    static final GenericContainer<?> CARRIER = new GenericContainer<>(
            new ImageFromDockerfile("fulfillops-carrier-sim-test", false)
                    .withFileFromPath(".", Path.of("../carrier-sim").toAbsolutePath()))
            .withEnv("TIMEOUT_MS", "3000")
            .withExposedPorts(8091)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    static {
        CARRIER.start();
    }

    @DynamicPropertySource
    static void carrierUrl(DynamicPropertyRegistry registry) {
        registry.add("fulfillops.carrier.base-url",
                () -> "http://" + CARRIER.getHost() + ":" + CARRIER.getMappedPort(8091));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobRunner jobs;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetCarrier() throws Exception {
        carrier("POST", "/admin/reset", "{}");
    }

    /**
     * The scenario this module exists for: the carrier books the shipment, then the connection drops before
     * we see the response. We must retry (we cannot know it worked) and must not end up with two shipments.
     */
    @Test
    void lostResponseAfterSuccessfulBookingYieldsExactlyOneShipment() throws Exception {
        long product = createProductWithStock(10);
        Packed packed = packOrder(product, 4);
        fault("DROP_AFTER_SUCCESS", 1);

        awaitShipmentStatus(packed.shipmentId(), "BOOKED");

        assertThat(carrierBookingsForOrder(packed.orderNumber()))
                .as("shipments the carrier holds for %s, under any key", packed.orderNumber()).isEqualTo(1);
        JsonNode atCarrier = read(carrier("GET", "/shipments?idempotencyKey=" + packed.key(), null));
        assertThat(atCarrier).as("carrier-side bookings for this key").hasSize(1);
        assertThat(read(carrier("GET", "/admin/stats", null)).get("replays").asInt()).isEqualTo(1);

        Map<String, Object> ours = jdbc.queryForMap(
                "select status, carrier_shipment_id, tracking_number, booking_attempts from shipments where id = ?::uuid",
                packed.shipmentId());
        assertThat(ours.get("carrier_shipment_id")).isEqualTo(atCarrier.get(0).get("shipmentId").asText());
        assertThat(ours.get("tracking_number")).isEqualTo(atCarrier.get(0).get("trackingNumber").asText());
        assertThat(ours.get("booking_attempts")).isEqualTo(2);
        assertThat(eventTypes(packed.shipmentId()))
                .containsExactly("PACKED", "BOOKING_REQUESTED", "BOOKING_ATTEMPT_FAILED", "BOOKED");
        assertThat(lastEventDetail(packed.shipmentId())).contains("already had for this key");

        assertShippedAndStockConsumed(packed, product, 10 - 4);
    }

    @Test
    void carrierOutageIsRetriedWithBackoffUntilItRecovers() throws Exception {
        long product = createProductWithStock(5);
        Packed packed = packOrder(product, 2);
        fault("FAIL", 3);

        awaitShipmentStatus(packed.shipmentId(), "BOOKED");

        assertThat(read(carrier("GET", "/shipments?idempotencyKey=" + packed.key(), null))).hasSize(1);
        assertThat(carrierBookingsForOrder(packed.orderNumber())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select booking_attempts from shipments where id = ?::uuid", Integer.class,
                packed.shipmentId())).isEqualTo(4);
        assertThat(eventTypes(packed.shipmentId()).stream().filter("BOOKING_ATTEMPT_FAILED"::equals)).hasSize(3);
        assertShippedAndStockConsumed(packed, product, 3);
    }

    @Test
    void timeoutIsTreatedAsUnknownOutcomeAndRetried() throws Exception {
        long product = createProductWithStock(5);
        Packed packed = packOrder(product, 1);
        fault("TIMEOUT", 1);

        awaitShipmentStatus(packed.shipmentId(), "BOOKED");

        assertThat(read(carrier("GET", "/shipments?idempotencyKey=" + packed.key(), null))).hasSize(1);
        assertThat(carrierBookingsForOrder(packed.orderNumber())).isEqualTo(1);
        assertThat(lastFailureDetail(packed.shipmentId())).contains("HttpTimeoutException");
        assertShippedAndStockConsumed(packed, product, 4);
    }

    @Test
    void exhaustedRetriesParkTheShipmentUntilASupervisorRetries() throws Exception {
        long product = createProductWithStock(5);
        Packed packed = packOrder(product, 1);
        fault("FAIL", 8); // max_attempts is 8

        awaitShipmentStatus(packed.shipmentId(), "FAILED");
        assertThat(jdbc.queryForObject("select status from outbox_jobs where type = 'CREATE_SHIPMENT' and aggregate_id = ?",
                String.class, packed.shipmentId())).isEqualTo("FAILED");
        assertThat(orderStatus(packed.orderId())).isEqualTo("PACKED");
        assertThat(jdbc.queryForObject("select reserved from stock_levels where product_id = ?", Integer.class, product))
                .as("stock stays reserved while the parcel sits on the dock").isEqualTo(1);

        mvc.perform(post("/api/shipments/{id}/retry", packed.shipmentId()).header("Authorization", bearer("warehouse")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/shipments/{id}/retry", packed.shipmentId()).header("Authorization", bearer("supervisor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

        awaitShipmentStatus(packed.shipmentId(), "BOOKED");
        assertThat(read(carrier("GET", "/shipments?idempotencyKey=" + packed.key(), null))).hasSize(1);
        assertThat(carrierBookingsForOrder(packed.orderNumber())).isEqualTo(1);
        assertShippedAndStockConsumed(packed, product, 4);
    }

    @Test
    void redeliveredJobAfterBookingIsANoOp() throws Exception {
        long product = createProductWithStock(5);
        Packed packed = packOrder(product, 2);
        awaitShipmentStatus(packed.shipmentId(), "BOOKED");

        // at-least-once delivery: the same job runs again
        jdbc.update("update outbox_jobs set status = 'PENDING', next_attempt_at = now() where aggregate_id = ?",
                packed.shipmentId());
        jobs.drain();

        assertThat(read(carrier("GET", "/admin/stats", null)).get("requests").asInt()).isEqualTo(1);
        assertShippedAndStockConsumed(packed, product, 3);
    }

    // ------------------------------------------------------------------ helpers

    record Packed(long orderId, String orderNumber, String shipmentId, String key) {
    }

    private Packed packOrder(long product, int qty) throws Exception {
        JsonNode order = submitOrder(product, qty);
        long orderId = order.get("id").asLong();
        long pickId = read(mvc.perform(post("/api/fulfillment/orders/{id}/pick-list", orderId)
                .header("Authorization", bearer("warehouse"))).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
        mvc.perform(post("/api/fulfillment/pick-lists/{id}/lines/1", pickId).header("Authorization", bearer("warehouse"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"picked\":true}"));
        mvc.perform(post("/api/fulfillment/pick-lists/{id}/complete", pickId).header("Authorization", bearer("warehouse")));
        JsonNode shipment = read(mvc.perform(post("/api/fulfillment/pick-lists/{id}/pack", pickId)
                        .header("Authorization", bearer("warehouse"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parcels\":1,\"weightKg\":2.5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Packed(orderId, order.get("orderNumber").asText(), shipment.get("id").asText(),
                shipment.get("carrierIdempotencyKey").asText());
    }

    private void awaitShipmentStatus(String shipmentId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String current = null;
        while (System.nanoTime() < deadline) {
            jobs.drain();
            current = jdbc.queryForObject("select status from shipments where id = ?::uuid", String.class, shipmentId);
            if (expected.equals(current)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("shipment " + shipmentId + " is " + current + ", expected " + expected
                + "; events " + eventTypes(shipmentId));
    }

    private void assertShippedAndStockConsumed(Packed packed, long product, int expectedOnHand) {
        assertThat(orderStatus(packed.orderId())).isEqualTo("SHIPPED");
        Map<String, Object> stock = jdbc.queryForMap("select on_hand, reserved from stock_levels where product_id = ?",
                product);
        assertThat(stock).containsEntry("on_hand", expectedOnHand).containsEntry("reserved", 0);
        assertThat(jdbc.queryForList("select type from inventory_movements where reference_id = ? order by id",
                String.class, packed.orderNumber())).containsExactly("RESERVE", "SHIP");
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private List<String> eventTypes(String shipmentId) {
        return new ArrayList<>(jdbc.queryForList("select type from shipment_events where shipment_id = ?::uuid order by id",
                String.class, shipmentId));
    }

    private String lastEventDetail(String shipmentId) {
        return jdbc.queryForObject("select detail from shipment_events where shipment_id = ?::uuid order by id desc limit 1",
                String.class, shipmentId);
    }

    private String lastFailureDetail(String shipmentId) {
        return jdbc.queryForObject("""
                select detail from shipment_events where shipment_id = ?::uuid and type = 'BOOKING_ATTEMPT_FAILED'
                order by id desc limit 1""", String.class, shipmentId);
    }

    private int carrierBookingsForOrder(String orderNumber) throws Exception {
        int n = 0;
        for (JsonNode b : read(carrier("GET", "/shipments", null))) {
            if (orderNumber.equals(b.get("reference").asText())) {
                n++;
            }
        }
        return n;
    }

    private void fault(String mode, int times) throws Exception {
        carrier("POST", "/admin/faults", "{\"mode\":\"%s\",\"times\":%d}".formatted(mode, times));
    }

    private String carrier(String method, String path, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://" + CARRIER.getHost() + ":" + CARRIER.getMappedPort(8091) + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
