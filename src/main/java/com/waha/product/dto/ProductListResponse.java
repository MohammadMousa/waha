package com.waha.product.dto;

import java.util.List;

public record ProductListResponse(List<ProductView> products, int page, int size, boolean hasMore) {}
