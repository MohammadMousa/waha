package com.waha.product.dto;

import com.waha.product.Product;

import java.util.List;

public record ProductListResponse(List<Product> products, int page, int size, boolean hasMore) {}
