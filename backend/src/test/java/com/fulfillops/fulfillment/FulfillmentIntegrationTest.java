package com.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fulfillops.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class FulfillmentIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pickPackAndRequestABooking() throws Exception {
        long p = createProductWithStock(10);
        JsonNode order = submitOrder(p, 4);
        long orderId = order.get("id").asLong();

        mvc.perform(get("/api/fulfillment/pick-queue").header("Authorization", bearer("warehouse")))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(orderId)).exists());

        JsonNode pick = read(as("warehouse", post("/api/fulfillment/orders/{id}/pick-list", orderId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PICKING"))
                .andReturn().getResponse().getContentAsString());
        long pickId = pick.get("id").asLong();

        as("warehouse", post("/api/fulfillment/pick-lists/{id}/complete", pickId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PICK_INCOMPLETE"));
        as("warehouse", post("/api/fulfillment/pick-lists/{id}/pack", pickId), "{\"parcels\":1,\"weightKg\":3}")
                .andExpect(status().isConflict());

        as("warehouse", post("/api/fulfillment/pick-lists/{id}/lines/1", pickId), "{\"picked\":true}")
                .andExpect(jsonPath("$.lines[0].picked").value(true))
                .andExpect(jsonPath("$.lines[0].pickedBy").value("warehouse"));
        as("warehouse", post("/api/fulfillment/pick-lists/{id}/complete", pickId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/api/fulfillment/pack-queue").header("Authorization", bearer("warehouse")))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(pickId)).exists());

        JsonNode shipment = read(as("warehouse", post("/api/fulfillment/pick-lists/{id}/pack", pickId),
                "{\"parcels\":2,\"weightKg\":18.5}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.carrierIdempotencyKey").value(org.hamcrest.Matchers.startsWith("fo-shp-")))
                .andExpect(jsonPath("$.events[0].type").value("PACKED"))
                .andExpect(jsonPath("$.events[1].type").value("BOOKING_REQUESTED"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.status").value("PACKED"));
        assertThat(jdbc.queryForObject(
                "select status from outbox_jobs where type = 'CREATE_SHIPMENT' and aggregate_id = ?", String.class,
                shipment.get("id").asText())).isEqualTo("PENDING");
        // stock is still reserved until the carrier confirms
        assertThat(jdbc.queryForObject("select reserved from stock_levels where product_id = ?", Integer.class, p))
                .isEqualTo(4);
    }

    @Test
    void twoPickersRacingForOneOrderOnlyOneWins() throws Exception {
        long p = createProductWithStock(5);
        long orderId = submitOrder(p, 1).get("id").asLong();
        bearer("warehouse");
        bearer("supervisor");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String user : List.of("warehouse", "supervisor")) {
            results.add(pool.submit(() -> {
                go.await();
                return as(user, post("/api/fulfillment/orders/{id}/pick-list", orderId)).andReturn().getResponse()
                        .getStatus();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) {
            codes.add(f.get());
        }
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from pick_lists where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    void cancellingDuringPickingReleasesStockAndClosesThePick() throws Exception {
        long p = createProductWithStock(6);
        long orderId = submitOrder(p, 6).get("id").asLong();
        long pickId = read(as("warehouse", post("/api/fulfillment/orders/{id}/pick-list", orderId))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        as("sales", post("/api/orders/{id}/cancel", orderId)).andExpect(status().isForbidden());
        as("supervisor", post("/api/orders/{id}/cancel", orderId), "{\"reason\":\"customer withdrew\"}")
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(jdbc.queryForObject("select available from stock_levels where product_id = ?", Integer.class, p))
                .isEqualTo(6);
        as("warehouse", post("/api/fulfillment/pick-lists/{id}/lines/1", pickId), "{\"picked\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKABLE"));
        mvc.perform(get("/api/fulfillment/pick-lists").header("Authorization", bearer("warehouse")))
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(pickId)).doesNotExist());
    }

    @Test
    void exceptionQueueShowsShortfallsForSupervisorsOnly() throws Exception {
        long p = createProductWithStock(2);
        long orderId = submitOrder(p, 5).get("id").asLong();

        mvc.perform(get("/api/fulfillment/exceptions").header("Authorization", bearer("warehouse")))
                .andExpect(status().isForbidden());
        String path = "$[?(@.orderId == %d)]".formatted(orderId);
        mvc.perform(get("/api/fulfillment/exceptions").header("Authorization", bearer("supervisor")))
                .andExpect(jsonPath(path + ".lines[0].shortBy").value(3))
                .andExpect(jsonPath(path + ".fulfillableNow").value(false));

        as("warehouse", post("/api/inventory/stock/{id}/receipts", p), "{\"quantity\":3}");
        mvc.perform(get("/api/fulfillment/exceptions").header("Authorization", bearer("supervisor")))
                .andExpect(jsonPath(path + ".fulfillableNow").value(true));
    }

    @Test
    void salesCannotPickAndWarehouseCannotPickUnreservedOrders() throws Exception {
        long p = createProductWithStock(1);
        long reserved = submitOrder(p, 1).get("id").asLong();
        as("sales", post("/api/fulfillment/orders/{id}/pick-list", reserved)).andExpect(status().isForbidden());

        long draft = read(postOrder("sales", null, false, orderJson(p, 1)).andReturn().getResponse()
                .getContentAsString()).get("id").asLong();
        as("warehouse", post("/api/fulfillment/orders/{id}/pick-list", draft))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    private ResultActions as(String user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.header("Authorization", bearer(user)));
    }

    private ResultActions as(String user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                             String body) throws Exception {
        return mvc.perform(req.header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
