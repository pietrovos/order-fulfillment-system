package com.fulfillops.catalog.internal;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    @Query("""
            select p from Product p
            where (:activeOnly = false or p.active = true)
              and (:q = '' or lower(p.sku) like lower(concat('%', :q, '%'))
                           or lower(p.name) like lower(concat('%', :q, '%')))
            """)
    List<Product> search(@Param("q") String q, @Param("activeOnly") boolean activeOnly, Sort sort);
}
