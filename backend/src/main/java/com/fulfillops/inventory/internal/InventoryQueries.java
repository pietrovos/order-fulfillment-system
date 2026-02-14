package com.fulfillops.inventory.internal;

import com.fulfillops.catalog.CatalogService;
import com.fulfillops.catalog.ProductSummary;
import com.fulfillops.inventory.MovementView;
import com.fulfillops.inventory.StockLevelView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side. Product names come through the catalog API rather than a cross-module SQL join. */
@Repository
public class InventoryQueries {

    private final JdbcTemplate jdbc;
    private final StockLedger ledger;
    private final CatalogService catalog;

    InventoryQueries(JdbcTemplate jdbc, StockLedger ledger, CatalogService catalog) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.catalog = catalog;
    }

    private record Row(long productId, int onHand, int reserved, int available, int reorderPoint,
                       java.time.Instant updatedAt) {
    }

    public List<StockLevelView> stockLevels(String search) {
        List<Row> rows = jdbc.query("""
                select product_id, on_hand, reserved, available, reorder_point, updated_at
                from stock_levels where warehouse_id = ?
                """, InventoryQueries::row, ledger.warehouseId());
        Map<Long, ProductSummary> products = catalog.summaries(rows.stream().map(Row::productId).toList());
        String q = search == null ? "" : search.trim().toLowerCase();
        return rows.stream()
                .map(r -> view(r, products.get(r.productId())))
                .filter(v -> q.isEmpty() || v.sku().toLowerCase().contains(q) || v.name().toLowerCase().contains(q))
                .sorted(Comparator.comparing(StockLevelView::sku))
                .toList();
    }

    public Optional<StockLevelView> stockLevel(long productId) {
        List<Row> rows = jdbc.query("""
                select product_id, on_hand, reserved, available, reorder_point, updated_at
                from stock_levels where warehouse_id = ? and product_id = ?
                """, InventoryQueries::row, ledger.warehouseId(), productId);
        return rows.stream().findFirst()
                .map(r -> view(r, catalog.summaries(List.of(productId)).get(productId)));
    }

    public boolean productExists(long productId) {
        return !catalog.summaries(List.of(productId)).isEmpty();
    }

    public void setReorderPoint(long productId, int reorderPoint) {
        ledger.ensureRow(productId);
        jdbc.update("update stock_levels set reorder_point = ?, updated_at = now() where product_id = ? and warehouse_id = ?",
                reorderPoint, productId, ledger.warehouseId());
    }

    public Page<MovementView> movements(Long productId, String referenceId, String type, int page, int size) {
        StringBuilder where = new StringBuilder(" where warehouse_id = ?");
        List<Object> args = new ArrayList<>(List.of(ledger.warehouseId()));
        if (productId != null) {
            where.append(" and product_id = ?");
            args.add(productId);
        }
        if (referenceId != null && !referenceId.isBlank()) {
            where.append(" and reference_id = ?");
            args.add(referenceId.trim());
        }
        if (type != null && !type.isBlank()) {
            where.append(" and type = ?");
            args.add(type.trim().toUpperCase());
        }
        Long total = jdbc.queryForObject("select count(*) from inventory_movements" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<MovementView> content = withSkus(jdbc.query(
                "select * from inventory_movements" + where + " order by id desc limit ? offset ?",
                InventoryQueries::movement, args.toArray()));
        return new PageImpl<>(content, PageRequest.of(page, size), total == null ? 0 : total);
    }

    public List<MovementView> movementsForReference(String referenceType, String referenceId) {
        return withSkus(jdbc.query(
                "select * from inventory_movements where reference_type = ? and reference_id = ? order by id",
                InventoryQueries::movement, referenceType, referenceId));
    }

    private List<MovementView> withSkus(List<MovementView> rows) {
        Set<Long> ids = rows.stream().map(MovementView::productId).collect(Collectors.toSet());
        Map<Long, ProductSummary> products = catalog.summaries(ids);
        return rows.stream().map(m -> new MovementView(m.id(), m.productId(),
                products.containsKey(m.productId()) ? products.get(m.productId()).sku() : "?", m.type(),
                m.onHandDelta(), m.reservedDelta(), m.onHandAfter(), m.reservedAfter(), m.referenceType(),
                m.referenceId(), m.reason(), m.actor(), m.createdAt())).toList();
    }

    private static StockLevelView view(Row r, ProductSummary p) {
        return new StockLevelView(r.productId(), p == null ? "?" : p.sku(), p == null ? "?" : p.name(), r.onHand(),
                r.reserved(), r.available(), r.reorderPoint(), r.available() <= r.reorderPoint(), r.updatedAt());
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getLong("product_id"), rs.getInt("on_hand"), rs.getInt("reserved"), rs.getInt("available"),
                rs.getInt("reorder_point"), rs.getTimestamp("updated_at").toInstant());
    }

    private static MovementView movement(ResultSet rs, int i) throws SQLException {
        return new MovementView(rs.getLong("id"), rs.getLong("product_id"), null, rs.getString("type"),
                rs.getInt("on_hand_delta"), rs.getInt("reserved_delta"), rs.getInt("on_hand_after"),
                rs.getInt("reserved_after"), rs.getString("reference_type"), rs.getString("reference_id"),
                rs.getString("reason"), rs.getString("actor"), rs.getTimestamp("created_at").toInstant());
    }
}
