/**
 * Stock positions and the append-only movement ledger. All writes go through
 * {@link com.fulfillops.inventory.InventoryService}; each one is a single guarded UPDATE plus one
 * ledger row in the same transaction.
 */
@ApplicationModule(displayName = "Inventory", allowedDependencies = {"shared", "catalog"})
package com.fulfillops.inventory;

import org.springframework.modulith.ApplicationModule;
