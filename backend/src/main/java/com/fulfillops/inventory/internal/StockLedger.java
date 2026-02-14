package com.fulfillops.inventory.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The only code that writes stock_levels. Every method is one guarded statement
 * ({@code UPDATE ... WHERE <invariant> RETURNING ...}) followed by one ledger insert, and must run
 * inside the caller's transaction. A guard that fails returns empty and changes nothing.
 *
 * <p>Postgres takes a row lock during the UPDATE. Under READ COMMITTED, a second transaction
 * blocked on that row re-evaluates the
 * WHERE clause against the newly committed version before applying its change. The condition and write
 * are atomic and do not require a separate SELECT ... FOR UPDATE round trip.
 */
@Repository
public class StockLedger {

    public enum MovementType { RECEIPT, ADJUSTMENT, RESERVE, RELEASE, SHIP }

    public record Position(int onHand, int reserved) {
        public int available() {
            return onHand - reserved;
        }
    }

    public record Reference(String type, String id, String reason, String actor) {
    }

    private final JdbcTemplate jdbc;
    private volatile Long warehouseId;

    StockLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long warehouseId() {
        Long id = warehouseId;
        if (id == null) {
            id = jdbc.queryForObject("select id from warehouses where code = 'MAIN'", Long.class);
            warehouseId = id;
        }
        return id;
    }

    public void ensureRow(long productId) {
        jdbc.update("""
                insert into stock_levels (product_id, warehouse_id) values (?, ?)
                on conflict (product_id, warehouse_id) do nothing
                """, productId, warehouseId());
    }

    public Position receive(long productId, int qty, Reference ref) {
        Position p = jdbc.queryForObject("""
                insert into stock_levels (product_id, warehouse_id, on_hand) values (?, ?, ?)
                on conflict (product_id, warehouse_id)
                do update set on_hand = stock_levels.on_hand + excluded.on_hand, updated_at = now()
                returning on_hand, reserved
                """, (rs, i) -> new Position(rs.getInt(1), rs.getInt(2)), productId, warehouseId(), qty);
        record(productId, MovementType.RECEIPT, qty, 0, p, ref);
        return p;
    }

    /** Changes on_hand by delta, refusing to go below what is already reserved. */
    public Optional<Position> adjust(long productId, int delta, Reference ref) {
        return apply(productId, MovementType.ADJUSTMENT, delta, 0, ref, """
                update stock_levels set on_hand = on_hand + ?, updated_at = now()
                where product_id = ? and warehouse_id = ? and on_hand + ? >= reserved
                returning on_hand, reserved
                """, delta, productId, warehouseId(), delta);
    }

    /** The reservation primitive: succeeds only if enough is available at the moment the row is locked. */
    public Optional<Position> reserve(long productId, int qty, Reference ref) {
        return apply(productId, MovementType.RESERVE, 0, qty, ref, """
                update stock_levels set reserved = reserved + ?, updated_at = now()
                where product_id = ? and warehouse_id = ? and on_hand - reserved >= ?
                returning on_hand, reserved
                """, qty, productId, warehouseId(), qty);
    }

    public Optional<Position> release(long productId, int qty, Reference ref) {
        return apply(productId, MovementType.RELEASE, 0, -qty, ref, """
                update stock_levels set reserved = reserved - ?, updated_at = now()
                where product_id = ? and warehouse_id = ? and reserved >= ?
                returning on_hand, reserved
                """, qty, productId, warehouseId(), qty);
    }

    /** Goods leave the building: consumes the reservation and the physical stock together. */
    public Optional<Position> ship(long productId, int qty, Reference ref) {
        return apply(productId, MovementType.SHIP, -qty, -qty, ref, """
                update stock_levels set on_hand = on_hand - ?, reserved = reserved - ?, updated_at = now()
                where product_id = ? and warehouse_id = ? and reserved >= ? and on_hand >= ?
                returning on_hand, reserved
                """, qty, qty, productId, warehouseId(), qty, qty);
    }

    public int available(long productId) {
        List<Integer> rows = jdbc.queryForList(
                "select available from stock_levels where product_id = ? and warehouse_id = ?",
                Integer.class, productId, warehouseId());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private Optional<Position> apply(long productId, MovementType type, int onHandDelta, int reservedDelta,
                                     Reference ref, String sql, Object... args) {
        List<Position> rows = jdbc.query(sql, (rs, i) -> new Position(rs.getInt(1), rs.getInt(2)), args);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Position after = rows.get(0);
        record(productId, type, onHandDelta, reservedDelta, after, ref);
        return Optional.of(after);
    }

    private void record(long productId, MovementType type, int onHandDelta, int reservedDelta, Position after,
                       Reference ref) {
        jdbc.update("""
                insert into inventory_movements (product_id, warehouse_id, type, on_hand_delta, reserved_delta,
                    on_hand_after, reserved_after, reference_type, reference_id, reason, actor)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, productId, warehouseId(), type.name(), onHandDelta, reservedDelta, after.onHand(),
                after.reserved(), ref.type(), ref.id(), ref.reason(), ref.actor());
    }
}
