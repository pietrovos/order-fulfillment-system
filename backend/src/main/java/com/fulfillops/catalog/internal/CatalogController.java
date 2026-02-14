package com.fulfillops.catalog.internal;

import com.fulfillops.catalog.CatalogService;
import com.fulfillops.catalog.ProductCommand;
import com.fulfillops.catalog.ProductSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<ProductSummary> list(@RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "false") boolean activeOnly) {
        return catalog.list(q, activeOnly);
    }

    @GetMapping("/{id}")
    ProductSummary get(@PathVariable Long id) {
        return catalog.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProductSummary create(@Valid @RequestBody ProductCommand cmd) {
        return catalog.create(cmd);
    }

    @PutMapping("/{id}")
    ProductSummary update(@PathVariable Long id, @Valid @RequestBody ProductCommand cmd) {
        return catalog.update(id, cmd);
    }
}
