package com.waha.sync;

import com.waha.store.StoreRepository;
import org.springframework.stereotype.Component;

// The one place that maps a sync table to its query, shared by the single feed call and the combined pull.
@Component
public class SyncFeedReader {

    private final SyncFeedRepository feeds;
    private final StoreRepository storeRepository;

    public SyncFeedReader(SyncFeedRepository feeds, StoreRepository storeRepository) {
        this.feeds = feeds;
        this.storeRepository = storeRepository;
    }

    public SyncFeedSource forStore(long orgId, long storeId) {
        return (table, since, afterId, limit) -> switch (table) {
            case PRODUCTS -> feeds.products(orgId, since, afterId, limit, storeRepository.getStoreConfig(storeId).vatRate());
            case BARCODES -> feeds.barcodes(orgId, since, afterId, limit);
            case CATEGORIES -> feeds.categories(orgId, since, afterId, limit);
            case PAYMENT_METHODS -> feeds.paymentMethods(since, afterId, limit);
            case STORE_PAYMENT_METHODS -> feeds.storePaymentMethods(storeId, since, afterId, limit);
            case PRODUCT_IMAGES -> feeds.productImages(orgId, since, afterId, limit);
            case REMOVALS -> throw new IllegalArgumentException("removals has no feed");
        };
    }
}
