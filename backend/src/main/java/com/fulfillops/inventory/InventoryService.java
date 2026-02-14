package com.fulfillops.inventory;

import com.fulfillops.inventory.internal.InventoryQueries;
import com.fulfillops.inventory.internal.StockLedger;
import com.fulfillops.inventory.internal.StockLedger.Reference;
import com.fulfillops.shared.security.CurrentActor;
import com.fulfillops.shared.web.DomainException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory API. User-facing operations are role-checked here; the order-driven operations
 * ({@code reserve/release/ship}) are called by other modules inside their own transaction
 * (Propagation.MANDATORY) so the stock change and the order status change commit together.
 */
@Service
public class InventoryService {

    private final StockLedger ledger;
    private final InventoryQueries queries;

    InventoryService(StockLedger ledger, InventoryQueries queries) {
        this.ledger = ledger;
        this.queries = queries;
    }

    // ------------------------------------------------------------------ queries

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<StockLevelView> stockLevels(String search) {
        return queries.stockLevels(search);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public StockLevelView stockLevel(long productId) {
        return queries.stockLevel(productId).orElseThrow(() -> DomainException.notFound("Stock for product", productId));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Page<MovementView> movements(Long productId, String referenceId, String type, int page, int size) {
        return queries.movements(productId, referenceId, type, page, Math.min(Math.max(size, 1), 200));
    }

    /** Ledger rows for one order, oldest first (used by the order detail screen). */
    @Transactional(readOnly = true)
    public List<MovementView> movementsForOrder(String orderRef) {
        return queries.movementsForReference("ORDER", orderRef);
    }

    // ------------------------------------------------------------------ manual changes

    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional
    public StockLevelView receive(long productId, int quantity, String reason) {
        if (quantity <= 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Received quantity must be positive");
        }
        requireProduct(productId);
        ledger.receive(productId, quantity, manual(reason == null || reason.isBlank() ? "Goods receipt" : reason));
        return queries.stockLevel(productId).orElseThrow();
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional
    public StockLevelView adjust(long productId, int delta, String reason) {
        if (delta == 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Adjustment cannot be zero");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Adjustments need a reason");
        }
        requireProduct(productId);
        ledger.adjust(productId, delta, manual(reason)).orElseThrow(() -> new DomainException(HttpStatus.CONFLICT,
                "BELOW_RESERVED", "On-hand cannot drop below the quantity already reserved for orders"));
        return queries.stockLevel(productId).orElseThrow();
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional
    public StockLevelView setReorderPoint(long productId, int reorderPoint) {
        if (reorderPoint < 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Reorder point cannot be negative");
        }
        requireProduct(productId);
        queries.setReorderPoint(productId, reorderPoint);
        return queries.stockLevel(productId).orElseThrow();
    }

    // ------------------------------------------------------------------ order-driven changes

    /**
     * Reserves every line or none. Lines are merged per product and locked in product-id order, so
     * two orders touching the same products always acquire row locks in the same sequence and cannot
     * deadlock. On the first shortfall this throws, rolling back the caller's transaction, including
     * any lines already reserved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(String orderRef, List<StockLine> lines) {
        Reference ref = orderRef(orderRef, "Reserved for order");
        merged(lines).forEach((productId, qty) -> ledger.reserve(productId, qty, ref)
                .orElseThrow(() -> new InsufficientStockException(productId, qty, ledger.available(productId))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String orderRef, List<StockLine> lines, String reason) {
        Reference ref = orderRef(orderRef, reason);
        merged(lines).forEach((productId, qty) -> ledger.release(productId, qty, ref)
                .orElseThrow(() -> new IllegalStateException(
                        "Reservation for product " + productId + " on " + orderRef + " is missing")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void ship(String orderRef, List<StockLine> lines) {
        Reference ref = orderRef(orderRef, "Shipped");
        merged(lines).forEach((productId, qty) -> ledger.ship(productId, qty, ref)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot ship product " + productId + " on " + orderRef + ": not reserved")));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<Long, Integer> merged(List<StockLine> lines) {
        Map<Long, Integer> byProduct = new TreeMap<>(Comparator.naturalOrder());
        lines.forEach(l -> byProduct.merge(l.productId(), l.quantity(), Integer::sum));
        return byProduct;
    }

    private void requireProduct(long productId) {
        if (!queries.productExists(productId)) {
            throw DomainException.notFound("Product", productId);
        }
    }

    private static Reference manual(String reason) {
        return new Reference("MANUAL", null, reason, CurrentActor.username());
    }

    private static Reference orderRef(String orderRef, String reason) {
        return new Reference("ORDER", orderRef, reason, CurrentActor.username());
    }
}
