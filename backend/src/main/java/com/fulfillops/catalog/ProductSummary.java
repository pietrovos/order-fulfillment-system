package com.fulfillops.catalog;

import java.math.BigDecimal;

public record ProductSummary(Long id, String sku, String name, String description, BigDecimal unitPrice, boolean active) {
}
