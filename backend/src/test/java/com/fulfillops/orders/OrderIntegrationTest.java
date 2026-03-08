package com.fulfillops.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fulfillops.IntegrationTest;
import com.fulfillops.orders.internal.OrderFacts;
import com.fulfillops.shared.jobs.JobRunner;
import com.fulfillops.shared.jobs.Outbox;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OrderIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void draftGetsANumberSnapshotsAndATotal() throws Exception {
        long p = createProduct("Hand truck", "89.50");
        String body = """
                {"customerName":"Harbor Supply","customerEmail":"ops@harbor.example","shippingAddress":"1 Pier St",
                 "lines":[{"productId":%d,"quantity":3}]}""".formatted(p);
        JsonNode o = read(postOrder("sales", null, false, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(o.get("orderNumber").asText()).matches("SO-\\d{6}");
        assertThat(o.get("status").asText()).isEqualTo("DRAFT");
        assertThat(o.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("268.50"));
        assertThat(o.get("lines").get(0).get("productName").asText()).isEqualTo("Hand truck");
        assertThat(o.get("history").get(0).get("to").asText()).isEqualTo("DRAFT");
        assertThat(o.get("nextStatuses")).hasSize(2);
    }

    @Test
    void draftEditsUseOptimisticVersioning() throws Exception {
        long p = createProductWithStock(10);
        JsonNode o = read(postOrder("sales", null, false, orderJson(p, 1)).andReturn().getResponse().getContentAsString());
        long id = o.get("id").asLong();
        long v = o.get("version").asLong();
        mvc.perform(put("/api/orders/{id}", id).param("version", String.valueOf(v))
                        .header("Authorization", bearer("sales")).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("Edited Co", p, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Edited Co"))
                .andExpect(jsonPath("$.lines[0].quantity").value(4));
        // a second editor still holding the old version loses
        mvc.perform(put("/api/orders/{id}", id).param("version", String.valueOf(v))
                        .header("Authorization", bearer("supervisor")).contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("Stale Co", p, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void submittingWithStockReservesIt() throws Exception {
        long p = createProductWithStock(10);
        JsonNode o = submitOrder(p, 4);
        assertThat(o.get("status").asText()).isEqualTo("RESERVED");
        assertThat(statuses(o)).containsExactly("DRAFT", "SUBMITTED", "RESERVED");
        mvc.perform(get("/api/inventory/stock/{id}", p).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.reserved").value(4))
                .andExpect(jsonPath("$.available").value(6));
        mvc.perform(get("/api/orders/{id}/movements", o.get("id").asLong()).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$[0].type").value("RESERVE"))
                .andExpect(jsonPath("$[0].reservedDelta").value(4));
        assertThat(jdbc.queryForObject("select status from outbox_jobs where type = 'RESERVE_ORDER' and aggregate_id = ?",
                String.class, o.get("id").asText())).isEqualTo("DONE");
    }

    @Test
    void submittingWithoutStockLandsInStockException() throws Exception {
        long p = createProductWithStock(2);
        JsonNode o = submitOrder(p, 5);
        assertThat(o.get("status").asText()).isEqualTo("STOCK_EXCEPTION");
        assertThat(o.get("exceptionReason").asText()).contains("ordered 5, available 2");
        mvc.perform(get("/api/inventory/stock/{id}", p).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() throws Exception {
        long p = createProductWithStock(10);
        String key = UUID.randomUUID().toString();
        JsonNode first = read(postOrder("sales", key, true, orderJson(p, 2)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        JsonNode second = read(postOrder("sales", key, true, orderJson(p, 2)).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString());
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(count("select count(*) from orders where idempotency_key = ?", key)).isEqualTo(1);
        mvc.perform(get("/api/inventory/stock/{id}", p).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.reserved").value(2));
    }

    @Test
    void reusingAKeyForADifferentOrderIsRejected() throws Exception {
        long p = createProductWithStock(10);
        String key = UUID.randomUUID().toString();
        postOrder("sales", key, true, orderJson(p, 2)).andExpect(status().isCreated());
        postOrder("sales", key, true, orderJson(p, 3))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void racingDuplicateSubmissionsCreateExactlyOneOrder() throws Exception {
        long p = createProductWithStock(100);
        String key = UUID.randomUUID().toString();
        String body = orderJson(p, 3);
        bearer("sales");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<JsonNode>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return read(postOrder("sales", key, true, body).andReturn().getResponse().getContentAsString());
            }));
        }
        go.countDown();
        Set<Long> ids = new HashSet<>();
        for (Future<JsonNode> f : results) {
            ids.add(f.get().get("id").asLong());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(count("select count(*) from orders where idempotency_key = ?", key)).isEqualTo(1);
        mvc.perform(get("/api/inventory/stock/{id}", p).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.reserved").value(3));
    }

    @Test
    void invalidTransitionsAreRejected() throws Exception {
        long p = createProductWithStock(10);
        long id = submitOrder(p, 1).get("id").asLong();
        // already submitted: cannot submit again, cannot edit
        mvc.perform(post("/api/orders/{id}/submit", id).header("Authorization", bearer("sales")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        mvc.perform(put("/api/orders/{id}", id).param("version", "999").header("Authorization", bearer("sales"))
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson(p, 2)))
                .andExpect(status().isConflict());
        // RESERVED is not in STOCK_EXCEPTION, so a retry makes no sense
        mvc.perform(post("/api/orders/{id}/retry-reservation", id).header("Authorization", bearer("supervisor")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        mvc.perform(post("/api/orders/{id}/cancel", id).header("Authorization", bearer("sales"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"customer changed mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/{id}/cancel", id).header("Authorization", bearer("sales")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CANCELLED to CANCELLED")));
    }

    @Test
    void cancellingAReservedOrderReleasesItsStock() throws Exception {
        long p = createProductWithStock(10);
        JsonNode o = submitOrder(p, 6);
        mvc.perform(post("/api/orders/{id}/cancel", o.get("id").asLong()).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/inventory/stock/{id}", p).header("Authorization", bearer("sales")))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10));
        assertThat(jdbc.queryForList("select type from inventory_movements where reference_id = ? order by id",
                String.class, o.get("orderNumber").asText())).containsExactly("RESERVE", "RELEASE");
    }

    @Test
    void warehouseStaffCannotCreateOrders() throws Exception {
        long p = createProductWithStock(1);
        postOrder("warehouse", null, true, orderJson(p, 1)).andExpect(status().isForbidden());
    }

    @Test
    void validatesLines() throws Exception {
        long p = createProductWithStock(1);
        postOrder("sales", null, false, """
                {"customerName":"x","shippingAddress":"y","lines":[]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.lines").exists());
        postOrder("sales", null, false, """
                {"customerName":"x","shippingAddress":"y","lines":[{"productId":%d,"quantity":0}]}""".formatted(p))
                .andExpect(status().isBadRequest());
        postOrder("sales", null, false, """
                {"customerName":"x","shippingAddress":"y","lines":[{"productId":%d,"quantity":1},{"productId":%d,"quantity":2}]}"""
                .formatted(p, p))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_LINE"));
        postOrder("sales", "short", false, orderJson(p, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Autowired
    OrderFacts facts;

    @Autowired
    Outbox outbox;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JobRunner jobs;

    @Test
    void outboxJobFinishesAReservationInterruptedAfterCommit() throws Exception {
        long p = createProductWithStock(5);
        // Simulate a crash between "order committed" and "inline reservation": write the order exactly as
        // submission does, but never run the inline step.
        var cmd = new OrderCommand("Crashy Co", null, "addr", null, List.of(new OrderCommand.Line(p, 2)));
        long id = tx.execute(s -> facts.insert(cmd,
                List.of(new OrderFacts.LineDraft(p, "SKU", "Item", 2, BigDecimal.ONE)), true, "sales", null, null,
                outbox));
        assertThat(orderStatus(id)).isEqualTo("SUBMITTED");

        jobs.drain();

        assertThat(orderStatus(id)).isEqualTo("RESERVED");
        assertThat(count("select reserved from stock_levels where product_id = ?", p)).isEqualTo(2);
        // running the job again (at-least-once delivery) changes nothing
        jdbc.update("update outbox_jobs set status = 'PENDING' where type = 'RESERVE_ORDER' and aggregate_id = ?",
                String.valueOf(id));
        jobs.drain();
        assertThat(count("select reserved from stock_levels where product_id = ?", p)).isEqualTo(2);
    }

    private String orderStatus(long id) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, id);
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private static List<String> statuses(JsonNode order) {
        List<String> out = new ArrayList<>();
        order.get("history").forEach(h -> out.add(h.get("to").asText()));
        return out;
    }
}
