package com.waha.store;

import java.math.BigDecimal;

public record StoreConfig(long id, String currency, BigDecimal vatRate) {}
