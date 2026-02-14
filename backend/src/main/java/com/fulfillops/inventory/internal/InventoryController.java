package com.fulfillops.inventory.internal;

import com.fulfillops.inventory.InventoryService;
import com.fulfillops.inventory.MovementView;
import com.fulfillops.inventory.StockLevelView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import com.fulfillops.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/stock")
    List<StockLevelView> stock(@RequestParam(required = false) String q) {
        return inventory.stockLevels(q);
    }

    @GetMapping("/stock/{productId}")
    StockLevelView stock(@PathVariable long productId) {
        return inventory.stockLevel(productId);
    }

    @PostMapping("/stock/{productId}/receipts")
    StockLevelView receive(@PathVariable long productId, @Valid @RequestBody Receipt body) {
        return inventory.receive(productId, body.quantity(), body.reason());
    }

    @PostMapping("/stock/{productId}/adjustments")
    StockLevelView adjust(@PathVariable long productId, @Valid @RequestBody Adjustment body) {
        return inventory.adjust(productId, body.delta(), body.reason());
    }

    @PutMapping("/stock/{productId}/reorder-point")
    StockLevelView reorderPoint(@PathVariable long productId, @Valid @RequestBody ReorderPoint body) {
        return inventory.setReorderPoint(productId, body.reorderPoint());
    }

    @GetMapping("/movements")
    PageResponse<MovementView> movements(@RequestParam(required = false) Long productId,
                                 @RequestParam(required = false) String reference,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(inventory.movements(productId, reference, type, Math.max(page, 0), size));
    }

    record Receipt(@NotNull @Positive Integer quantity, @Size(max = 500) String reason) {
    }

    record Adjustment(@NotNull Integer delta, @NotBlank @Size(max = 500) String reason) {
    }

    record ReorderPoint(@NotNull @Min(0) Integer reorderPoint) {
    }
}
