package com.waha.product.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.product.Product;

import java.math.BigDecimal;
import java.util.List;

// A product as customer apps see it. price is with VAT unless the organization turned that off;
// priceWithVat is always the price with VAT.
public record ProductView(long id, String barcode, JsonNode name, JsonNode description, BigDecimal price,
                          boolean active, long companyId, boolean publicListed, Long categoryId,
                          Long imageResourceId, List<String> tags, BigDecimal priceWithVat) {

    public static ProductView of(Product p, BigDecimal priceWithVat, boolean showWithVat) {
        return new ProductView(p.id(), p.barcode(), p.name(), p.description(),
            showWithVat ? priceWithVat : p.price(), p.active(), p.companyId(), p.publicListed(), p.categoryId(),
            p.imageResourceId(), p.tags(), priceWithVat);
    }
}
