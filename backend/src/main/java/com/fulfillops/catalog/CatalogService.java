package com.fulfillops.catalog;

import com.fulfillops.catalog.internal.Product;
import com.fulfillops.catalog.internal.ProductRepository;
import com.fulfillops.shared.web.DomainException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    CatalogService(ProductRepository products, ApplicationEventPublisher events) {
        this.products = products;
        this.events = events;
    }

    @PreAuthorize("isAuthenticated()")
    public List<ProductSummary> list(String search, boolean activeOnly) {
        String q = search == null ? "" : search.trim();
        return products.search(q, activeOnly, Sort.by("sku")).stream().map(Product::toSummary).toList();
    }

    @PreAuthorize("isAuthenticated()")
    public ProductSummary get(Long id) {
        return find(id).toSummary();
    }

    /** Internal lookup used by other modules (no security check: callers are already authorized). */
    public Map<Long, ProductSummary> summaries(Collection<Long> ids) {
        return products.findAllById(ids).stream()
                .map(Product::toSummary)
                .collect(Collectors.toMap(ProductSummary::id, Function.identity()));
    }

    @Transactional
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ProductSummary create(ProductCommand cmd) {
        if (products.existsBySku(cmd.sku())) {
            throw new DomainException(HttpStatus.CONFLICT, "DUPLICATE_SKU", "SKU " + cmd.sku() + " already exists");
        }
        Product p = products.save(new Product(cmd.sku(), cmd.name(), cmd.description(), cmd.unitPrice()));
        events.publishEvent(new ProductCreated(p.getId()));
        return p.toSummary();
    }

    @Transactional
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ProductSummary update(Long id, ProductCommand cmd) {
        Product p = find(id);
        if (!p.getSku().equals(cmd.sku()) && products.existsBySku(cmd.sku())) {
            throw new DomainException(HttpStatus.CONFLICT, "DUPLICATE_SKU", "SKU " + cmd.sku() + " already exists");
        }
        p.update(cmd.sku(), cmd.name(), cmd.description(), cmd.unitPrice(), cmd.active() == null || cmd.active());
        return p.toSummary();
    }

    private Product find(Long id) {
        return products.findById(id).orElseThrow(() -> DomainException.notFound("Product", id));
    }
}
