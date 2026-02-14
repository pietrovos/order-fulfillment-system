package com.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fulfillops.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class InventoryIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InventoryService inventory;

    @Autowired
    TransactionTemplate tx;

    @Test
    void receiptIncreasesOnHandAndWritesALedgerRow() throws Exception {
        long id = createProductWithStock(0);
        mvc.perform(post("/api/inventory/stock/{id}/receipts", id).header("Authorization", bearer("warehouse"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":25,\"reason\":\"PO-1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(25))
                .andExpect(jsonPath("$.available").value(25));

        mvc.perform(get("/api/inventory/movements").param("productId", String.valueOf(id))
                        .header("Authorization", bearer("sales")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("RECEIPT"))
                .andExpect(jsonPath("$.content[0].onHandDelta").value(25))
                .andExpect(jsonPath("$.content[0].onHandAfter").value(25))
                .andExpect(jsonPath("$.content[0].actor").value("warehouse"))
                .andExpect(jsonPath("$.content[0].reason").value("PO-1001"));
    }

    @Test
    void adjustmentsAreSupervisorOnlyAndNeedAReason() throws Exception {
        long id = createProductWithStock(10);
        mvc.perform(post("/api/inventory/stock/{id}/adjustments", id).header("Authorization", bearer("warehouse"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-2,\"reason\":\"damaged\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/stock/{id}/adjustments", id).header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/inventory/stock/{id}/adjustments", id).header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-2,\"reason\":\"Cycle count\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(8));
    }

    @Test
    void cannotAdjustOnHandBelowWhatIsReserved() throws Exception {
        long id = createProductWithStock(10);
        tx.executeWithoutResult(s -> inventory.reserve("ORD-T1", List.of(new StockLine(id, 6))));

        mvc.perform(post("/api/inventory/stock/{id}/adjustments", id).header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-5,\"reason\":\"shrinkage\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BELOW_RESERVED"));
        assertThat(position(id)).containsEntry("on_hand", 10).containsEntry("reserved", 6);
    }

    @Test
    void ledgerReplaysToTheCurrentPosition() throws Exception {
        long id = createProductWithStock(20);
        tx.executeWithoutResult(s -> inventory.reserve("ORD-T2", List.of(new StockLine(id, 7))));
        tx.executeWithoutResult(s -> inventory.release("ORD-T2", List.of(new StockLine(id, 3)), "partial cancel"));
        tx.executeWithoutResult(s -> inventory.ship("ORD-T2", List.of(new StockLine(id, 4))));
        mvc.perform(post("/api/inventory/stock/{id}/adjustments", id).header("Authorization", bearer("supervisor"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1,\"reason\":\"damaged\"}"));

        Map<String, Object> sums = jdbc.queryForMap("""
                select sum(on_hand_delta)::int as on_hand, sum(reserved_delta)::int as reserved
                from inventory_movements where product_id = ?""", id);
        assertThat(sums).containsEntry("on_hand", 15).containsEntry("reserved", 0);
        assertThat(position(id)).containsEntry("on_hand", 15).containsEntry("reserved", 0);
        assertThat(jdbc.queryForList("select type from inventory_movements where product_id = ? order by id",
                String.class, id)).containsExactly("RECEIPT", "RESERVE", "RELEASE", "SHIP", "ADJUSTMENT");
    }

    @Test
    void ledgerIsAppendOnlyAtTheDatabaseLevel() throws Exception {
        long id = createProductWithStock(5);
        assertThatThrownBy(() -> jdbc.update("update inventory_movements set on_hand_delta = 500 where product_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from inventory_movements where product_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate inventory_movements cascade"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void databaseRejectsOverReservationEvenFromRawSql() throws Exception {
        long id = createProductWithStock(3);
        assertThatThrownBy(() -> jdbc.update("update stock_levels set reserved = 4 where product_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("stock_reserved_within_on_hand");
        assertThatThrownBy(() -> jdbc.update("update stock_levels set on_hand = -1 where product_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reservationIsAllOrNothingAcrossLines() throws Exception {
        long a = createProductWithStock(10);
        long b = createProductWithStock(2);
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                inventory.reserve("ORD-T3", List.of(new StockLine(a, 5), new StockLine(b, 3)))))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> assertThat(((InsufficientStockException) e).available()).isEqualTo(2));
        assertThat(position(a)).containsEntry("reserved", 0);
        assertThat(position(b)).containsEntry("reserved", 0);
        assertThat(jdbc.queryForObject("select count(*) from inventory_movements where reference_id = 'ORD-T3'",
                Integer.class)).isZero();
    }

    @Test
    void orderDrivenOperationsRequireAnExistingTransaction() throws Exception {
        long id = createProductWithStock(5);
        assertThatThrownBy(() -> inventory.reserve("ORD-T4", List.of(new StockLine(id, 1))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private Map<String, Object> position(long productId) {
        return jdbc.queryForMap("select on_hand, reserved from stock_levels where product_id = ?", productId);
    }
}
