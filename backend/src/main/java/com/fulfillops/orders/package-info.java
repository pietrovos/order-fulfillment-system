/**
 * Orders, line items and the order state machine. Owns idempotent submission and drives stock
 * reservation through the inventory API. Fulfillment moves orders through PICKING/PACKED/SHIPPED via
 * {@link com.fulfillops.orders.OrderService}; this module never calls fulfillment.
 */
@ApplicationModule(displayName = "Orders", allowedDependencies = {"shared", "catalog", "inventory"})
package com.fulfillops.orders;

import org.springframework.modulith.ApplicationModule;
