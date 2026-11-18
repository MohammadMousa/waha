package com.waha.integration.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.integration.ExternalMappingRepository;
import com.waha.integration.ExternalSystem;
import com.waha.integration.ExternalSystemRepository;
import com.waha.integration.SyncQueueItem;
import com.waha.integration.SyncQueueRepository;
import com.waha.order.OrderRepository;
import com.waha.product.Product;
import com.waha.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// An order line whose product Odoo does not know: find it by any barcode, or create it when the account allows it.
class OdooMissingProductTest {

    static final ObjectMapper JSON = new ObjectMapper();

    ExternalSystemRepository systems = mock(ExternalSystemRepository.class);
    ExternalMappingRepository mappings = mock(ExternalMappingRepository.class);
    OdooClient odoo = mock(OdooClient.class);
    ProductRepository products = mock(ProductRepository.class);
    OdooOrderSyncService service;
    ExternalSystem sys;
    SyncQueueItem item = new SyncQueueItem(1, 1, "ORDER", "o1", "CREATE", "{}", "PENDING", 0, null, 5L, null, null);

    @BeforeEach
    void setUp() throws Exception {
        service = new OdooOrderSyncService(systems, mock(SyncQueueRepository.class), mappings, mock(OrderRepository.class),
            odoo, JSON, mock(OdooPosOrderService.class), products);
        sys = system("POS");
        when(mappings.findByLocalId(anyLong(), eq("PRODUCT"), anyString())).thenReturn(Optional.empty());
        when(systems.createMissingProducts(1L)).thenReturn(false);
        when(products.findBarcodesByProduct(7L)).thenReturn(List.of(
            new ProductRepository.BarcodeEntry(1, "6221111111111", true),
            new ProductRepository.BarcodeEntry(2, "6222222222222", false)));
        Product p = new Product(7, "6221111111111", JSON.readTree("{\"en\":\"Tea\",\"ar\":\"شاي\"}"), JSON.readTree("{}"),
            new BigDecimal("12.50"), true, 1, true, null, null);
        when(products.findByIds(List.of(7L))).thenReturn(List.of(p));
    }

    private static ExternalSystem system(String target) {
        return new ExternalSystem(1, "ODOO", "https://x.odoo.com", "k", "u", null, target, 1L, true, null, null, null, null);
    }

    private JsonNode order() throws Exception {
        return JSON.readTree("{\"items\":[{\"productId\":7,\"barcode\":\"6221111111111\",\"name\":{\"en\":\"Tea\"},"
            + "\"quantity\":2,\"unitPrice\":14.0,\"lineTotal\":28.0}]}");
    }

    @Test
    void findsTheProductByAnExtraBarcodeWithOneSearch() throws Exception {
        when(odoo.search(any(), any(), any(), eq("product.product"), eq(List.of(List.of("barcode", "in",
            List.of("6221111111111", "6222222222222")))))).thenReturn(List.of(99L));
        var lines = service.resolveLines(sys, item, order());
        assertEquals(99L, lines.get(0).odooProductId());
        verify(mappings).save(1, "PRODUCT", "7", "99", 5L);
        verify(odoo, never()).create(any(), any(), any(), anyString(), any());
    }

    @Test
    void doesNotCreateWhenTheSwitchIsOff() throws Exception {
        when(odoo.search(any(), any(), any(), anyString(), any())).thenReturn(List.of());
        var lines = service.resolveLines(sys, item, order());
        assertNull(lines.get(0).odooProductId());
        verify(odoo, never()).create(any(), any(), any(), anyString(), any());
    }

    @Test
    void createsTheProductInTheDefaultCategoryWhenTheSwitchIsOn() throws Exception {
        when(systems.createMissingProducts(1L)).thenReturn(true);
        when(odoo.search(any(), any(), any(), anyString(), any())).thenReturn(List.of());
        when(odoo.create(any(), any(), any(), eq("product.category"), any())).thenReturn(50L);
        when(odoo.create(any(), any(), any(), eq("product.product"), any())).thenReturn(123L);
        var lines = service.resolveLines(sys, item, order());
        assertEquals(123L, lines.get(0).odooProductId());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> vals = ArgumentCaptor.forClass(Map.class);
        verify(odoo).create(any(), any(), any(), eq("product.product"), vals.capture());
        Map<String, Object> v = vals.getValue();
        assertEquals("Tea", v.get("name"));
        assertEquals("6221111111111", v.get("barcode"));          // the primary barcode
        assertEquals(12.5, v.get("list_price"));                   // stored price, without VAT
        assertEquals(50L, v.get("categ_id"));
        assertEquals(true, v.get("available_in_pos"));
        verify(odoo).create(any(), any(), any(), eq("product.category"), eq(Map.of("name", "waha_products")));
        verify(mappings).save(1, "PRODUCT", "7", "123", 5L);
    }

    @Test
    void reusesTheExistingDefaultCategoryAndSkipsThePosFlagForSalesOrders() throws Exception {
        when(systems.createMissingProducts(1L)).thenReturn(true);
        when(odoo.search(any(), any(), any(), eq("product.category"), any())).thenReturn(List.of(77L));
        when(odoo.search(any(), any(), any(), eq("product.product"), any())).thenReturn(List.of());
        when(odoo.create(any(), any(), any(), eq("product.product"), any())).thenReturn(124L);
        service.resolveLines(system("SALES"), item, order());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> vals = ArgumentCaptor.forClass(Map.class);
        verify(odoo).create(any(), any(), any(), eq("product.product"), vals.capture());
        assertEquals(77L, vals.getValue().get("categ_id"));
        assertFalse(vals.getValue().containsKey("available_in_pos"));
        verify(odoo, never()).create(any(), any(), any(), eq("product.category"), any());
    }

    @Test
    void neverCreatesAProductThatHasOnlyAPlaceholderBarcode() throws Exception {
        when(systems.createMissingProducts(1L)).thenReturn(true);
        when(products.findBarcodesByProduct(7L)).thenReturn(List.of(new ProductRepository.BarcodeEntry(1, "ODOO_55", true)));
        when(odoo.search(any(), any(), any(), anyString(), any())).thenReturn(List.of());
        var lines = service.resolveLines(sys, item, JSON.readTree(
            "{\"items\":[{\"productId\":7,\"barcode\":\"ODOO_55\",\"name\":{\"en\":\"Tea\"},\"quantity\":1,\"unitPrice\":1,\"lineTotal\":1}]}"));
        assertNull(lines.get(0).odooProductId());
        verify(odoo, never()).create(any(), any(), any(), anyString(), any());
    }
}
