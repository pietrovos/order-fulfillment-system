/**
 * Warehouse execution: pick lists, packing, carrier shipments and the shipment timeline. Drives the
 * order through PICKING/PACKED/SHIPPED via the orders API; nothing depends on this module.
 */
@ApplicationModule(displayName = "Fulfillment", allowedDependencies = {"shared", "orders", "inventory"})
package com.fulfillops.fulfillment;

import org.springframework.modulith.ApplicationModule;
