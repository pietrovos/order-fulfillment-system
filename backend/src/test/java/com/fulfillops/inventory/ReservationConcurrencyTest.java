package com.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fulfillops.HttpIntegrationTest;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves reservations are atomic under real concurrency: separate HTTP requests, separate Tomcat threads,
 * separate connections, one real Postgres. Every test creates its own products, so repetitions are independent.
 */
class ReservationConcurrencyTest extends HttpIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private static ExecutorService pool;

    @BeforeAll
    static void startPool() {
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterAll
    static void stopPool() {
        pool.shutdownNow();
    }

    /**
     * The headline scenario: 10 in stock, two customers order 7 at the same instant. Exactly one order is
     * RESERVED, the other lands in STOCK_EXCEPTION, and the books still balance. Repeated so that the race is
     * exercised many times per run; `scripts/stress-reservations.sh` repeats the whole class in a loop.
     */
    @RepeatedTest(value = 50, name = "two orders for 7 of 10 units, run {currentRepetition}/{totalRepetitions}")
    void twoConcurrentOrdersForSevenOfTenUnits() throws Exception {
        long product = createProduct(uniqueSku("RACE"), 10);
        CyclicBarrier start = new CyclicBarrier(2);

        Future<JsonNode> a = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return submit("Customer A", Map.of(product, 7));
        });
        Future<JsonNode> b = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return submit("Customer B", Map.of(product, 7));
        });
        List<String> outcomes = List.of(a.get(30, TimeUnit.SECONDS).get("status").asText(),
                b.get(30, TimeUnit.SECONDS).get("status").asText());

        assertThat(outcomes).containsExactlyInAnyOrder("RESERVED", "STOCK_EXCEPTION");
        assertThat(position(product)).containsEntry("on_hand", 10).containsEntry("reserved", 7)
                .containsEntry("available", 3);
        assertLedgerMatchesPosition(product);
    }

    /**
     * Deterministic version of the race. Connection 1 reserves 7 and holds its row lock without committing.
     * The HTTP request for another 7 must block on that row (not read a stale "10 available"), then, once
     * connection 1 commits, re-evaluate {@code on_hand - reserved >= 7} against the new row and fail.
     */
    @Test
    void aBlockedReservationReEvaluatesAgainstTheCommittedRow() throws Exception {
        long product = createProduct(uniqueSku("LOCK"), 10);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement(
                    "update stock_levels set reserved = reserved + 7 where product_id = ? and on_hand - reserved >= 7")) {
                st.setLong(1, product);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }

            CompletableFuture<JsonNode> contender =
                    CompletableFuture.supplyAsync(() -> submit("Contender", Map.of(product, 7)), pool);

            awaitBlockedOnRowLock();
            assertThat(contender).isNotDone();

            holder.commit();
            JsonNode order = contender.get(30, TimeUnit.SECONDS);
            assertThat(order.get("status").asText()).isEqualTo("STOCK_EXCEPTION");
            assertThat(order.get("exceptionReason").asText()).contains("ordered 7, available 3");
        }
        assertThat(position(product)).containsEntry("reserved", 7);
    }

    /**
     * Two multi-line orders that list the same products in opposite order. Without a consistent lock order
     * this is a textbook deadlock; the inventory service sorts lines by product id so it never happens.
     */
    @RepeatedTest(10)
    void oppositeLineOrderNeverDeadlocks() throws Exception {
        long p1 = createProduct(uniqueSku("DL"), 100);
        long p2 = createProduct(uniqueSku("DL"), 100);
        int pairs = 6;
        CyclicBarrier start = new CyclicBarrier(pairs * 2);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return submit("Forward", new java.util.LinkedHashMap<>(Map.of(p1, 1, p2, 1)));
            }));
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                var reversed = new java.util.LinkedHashMap<Long, Integer>();
                reversed.put(p2, 1);
                reversed.put(p1, 1);
                return submit("Reverse", reversed);
            }));
        }
        for (Future<JsonNode> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS).get("status").asText()).isEqualTo("RESERVED");
        }
        assertThat(position(p1)).containsEntry("reserved", pairs * 2);
        assertThat(position(p2)).containsEntry("reserved", pairs * 2);
    }

    /**
     * Many customers, few products, random quantities, some cancellations. Whatever the interleaving:
     * reserved stock equals exactly what RESERVED orders hold, nothing is over-reserved, and the ledger
     * replays to the current position.
     */
    @Test
    void randomisedLoadKeepsTheBooksBalanced() throws Exception {
        List<Long> products = List.of(createProduct(uniqueSku("MIX"), 15), createProduct(uniqueSku("MIX"), 15),
                createProduct(uniqueSku("MIX"), 15));
        int customers = 40;
        CyclicBarrier start = new CyclicBarrier(customers);
        List<Future<JsonNode>> futures = new ArrayList<>();
        ExecutorService crowd = Executors.newFixedThreadPool(customers);
        for (int i = 0; i < customers; i++) {
            futures.add(crowd.submit(() -> {
                var rnd = ThreadLocalRandom.current();
                var lines = new java.util.HashMap<Long, Integer>();
                for (long p : products) {
                    if (rnd.nextBoolean()) {
                        lines.put(p, rnd.nextInt(1, 5));
                    }
                }
                if (lines.isEmpty()) {
                    lines.put(products.get(0), 1);
                }
                start.await(10, TimeUnit.SECONDS);
                JsonNode order = submit("Load", lines);
                if ("RESERVED".equals(order.get("status").asText()) && rnd.nextInt(4) == 0) {
                    return cancel(order.get("id").asLong());
                }
                return order;
            }));
        }
        int reserved = 0;
        int exceptions = 0;
        for (Future<JsonNode> f : futures) {
            String status = f.get(60, TimeUnit.SECONDS).get("status").asText();
            assertThat(status).isIn("RESERVED", "STOCK_EXCEPTION", "CANCELLED");
            if (status.equals("RESERVED")) {
                reserved++;
            } else if (status.equals("STOCK_EXCEPTION")) {
                exceptions++;
            }
        }
        crowd.shutdown();
        assertThat(reserved).isPositive();
        assertThat(exceptions).as("40 customers ordering up to 12 units from 45 must exhaust something").isPositive();

        for (long p : products) {
            Integer heldByOrders = jdbc.queryForObject("""
                    select coalesce(sum(l.quantity), 0)::int from order_lines l join orders o on o.id = l.order_id
                    where l.product_id = ? and o.status in ('RESERVED', 'PICKING', 'PACKED')""", Integer.class, p);
            assertThat(position(p).get("reserved")).isEqualTo(heldByOrders);
            assertThat((Integer) position(p).get("available")).isGreaterThanOrEqualTo(0);
            assertLedgerMatchesPosition(p);
        }
    }

    private void awaitBlockedOnRowLock() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where wait_event_type = 'Lock' and query like 'update stock_levels set reserved%'""", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("contender never blocked on the stock row lock");
    }

    private Map<String, Object> position(long product) {
        return jdbc.queryForMap("select on_hand, reserved, available from stock_levels where product_id = ?", product);
    }

    private void assertLedgerMatchesPosition(long product) {
        Map<String, Object> sums = jdbc.queryForMap("""
                select coalesce(sum(on_hand_delta), 0)::int as on_hand, coalesce(sum(reserved_delta), 0)::int as reserved
                from inventory_movements where product_id = ?""", product);
        Map<String, Object> pos = position(product);
        assertThat(sums.get("on_hand")).isEqualTo(pos.get("on_hand"));
        assertThat(sums.get("reserved")).isEqualTo(pos.get("reserved"));
    }
}
