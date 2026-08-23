package com.waha.store;

import com.waha.store.dto.StoreSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreRepository storeRepository;

    public StoreController(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    // The store picker after login. Only ever returns public, active,
    // operational (CHILD) locations - never a PARENT grouping node or a
    // WAREHOUSE, since neither is somewhere a customer would ever check
    // out from.
    @GetMapping
    public List<StoreSummary> listPublicStores() {
        return storeRepository.findPublicStores();
    }
}
