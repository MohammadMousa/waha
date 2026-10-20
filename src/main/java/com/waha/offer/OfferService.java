package com.waha.offer;

import com.waha.product.Product;
import com.waha.product.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OfferService {

    public record Request(String name, String type, Long clientId, String discountMode, BigDecimal discountValue,
                          BigDecimal maxDiscountAmount, String startDate, String endDate, Boolean enabled,
                          String storeScope, List<Long> storeIds, List<Long> productIds) {}

    // Per-field messages; thrown for a 400 reply.
    public static class ValidationException extends RuntimeException {
        private final Map<String, String> errors;
        public ValidationException(Map<String, String> errors) {
            super("Validation failed");
            this.errors = errors;
        }
        public Map<String, String> errors() { return errors; }
    }

    private final OfferRepository repo;
    private final ProductRepository products;
    private final OfferClock clock;

    public OfferService(OfferRepository repo, ProductRepository products, OfferClock clock) {
        this.repo = repo;
        this.products = products;
        this.clock = clock;
    }

    public OfferClock clock() { return clock; }

    // existingType is set when editing: the type cannot change.
    public OfferRepository.Fields validate(Request r, long orgId, String existingType) {
        Map<String, String> e = new LinkedHashMap<>();

        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty()) e.put("name", "Name is required");
        else if (name.length() > 120) e.put("name", "Name must be at most 120 characters");

        String type = existingType != null ? existingType : (r.type() == null ? null : r.type().trim().toUpperCase());
        if (type == null || !(type.equals(Offer.GLOBAL) || type.equals(Offer.PRODUCT))) {
            e.put("type", "Type must be GLOBAL or PRODUCT");
        } else if (existingType != null && r.type() != null && !r.type().trim().equalsIgnoreCase(existingType)) {
            e.put("type", "Type cannot be changed after the offer is created");
        }

        String mode = r.discountMode() == null ? null : r.discountMode().trim().toUpperCase();
        if (mode == null || !(mode.equals(Offer.AMOUNT) || mode.equals(Offer.PERCENTAGE)))
            e.put("discountMode", "Discount mode must be AMOUNT or PERCENTAGE");

        BigDecimal value = r.discountValue();
        if (value == null || value.signum() <= 0) e.put("discountValue", "Discount value must be greater than 0");
        else if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) e.put("discountValue", "At most 2 decimals");
        else if (Offer.PERCENTAGE.equals(mode) && value.compareTo(BigDecimal.valueOf(100)) > 0)
            e.put("discountValue", "A percentage must be at most 100");

        BigDecimal max = r.maxDiscountAmount();
        if (max != null && max.signum() <= 0) e.put("maxDiscountAmount", "Maximum discount must be greater than 0");

        LocalDate start = parseDate(r.startDate(), "startDate", e);
        LocalDate end = parseDate(r.endDate(), "endDate", e);
        if (start != null && end != null && end.isBefore(start)) e.put("endDate", "End date must not be before the start date");

        String scope = r.storeScope() == null || r.storeScope().isBlank() ? "ALL" : r.storeScope().trim().toUpperCase();
        if (!("ALL".equals(scope) || "SOME".equals(scope))) e.put("storeScope", "Store scope must be ALL or SOME");

        List<Long> storeIds = r.storeIds() == null ? List.of() : r.storeIds().stream().filter(Objects::nonNull).distinct().toList();
        if ("SOME".equals(scope)) {
            if (storeIds.isEmpty()) e.put("storeIds", "Choose at least one store");
            else if (repo.countStoresOf(orgId, storeIds) != storeIds.size())
                e.put("storeIds", "Every store must belong to the client");
        } else {
            storeIds = List.of();
        }

        List<Long> productIds = r.productIds() == null ? List.of() : r.productIds().stream().filter(Objects::nonNull).distinct().toList();
        if (Offer.PRODUCT.equals(type)) {
            if (productIds.isEmpty()) e.put("productIds", "Choose at least one product");
            else if (repo.countProductsOf(orgId, productIds) != productIds.size())
                e.put("productIds", "Every product must belong to the client");
        } else {
            productIds = List.of();
        }

        if (!e.isEmpty()) throw new ValidationException(e);
        return new OfferRepository.Fields(orgId, name, type, mode, value.setScale(2, java.math.RoundingMode.HALF_UP),
            max == null ? null : max.setScale(2, java.math.RoundingMode.HALF_UP), start, end,
            r.enabled() == null || r.enabled(), scope, storeIds, productIds);
    }

    private static LocalDate parseDate(String s, String field, Map<String, String> errors) {
        if (s == null || s.isBlank()) {
            errors.put(field, "Date is required");
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException ex) {
            errors.put(field, "Use the format yyyy-MM-dd");
            return null;
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    public record SampleLine(long productId, int quantity) {}

    // The discount a draft offer would give on a sample, plus likely mistakes. Saves nothing.
    public Map<String, Object> preview(OfferRepository.Fields f, List<SampleLine> sample, BigDecimal cartTotal) {
        List<String> warnings = new ArrayList<>();
        Offer draft = new Offer(0, f.organizationId(), f.name(), f.type(), f.discountMode(), f.discountValue(),
            f.maxDiscountAmount(), f.startDate(), f.endDate(), true, f.storeScope(), new HashSet<>(f.productIds()));

        List<PricingEngine.Line> lines = new ArrayList<>();
        if (sample != null && !sample.isEmpty()) {
            Map<Long, Product> byId = products.findByIds(sample.stream().map(SampleLine::productId).toList())
                .stream().collect(Collectors.toMap(Product::id, p -> p));
            for (SampleLine s : sample) {
                Product p = byId.get(s.productId());
                if (p != null) lines.add(new PricingEngine.Line(p.id(), Math.max(1, s.quantity()), p.price()));
            }
        } else if (cartTotal != null && cartTotal.signum() > 0) {
            lines.add(new PricingEngine.Line(-1, 1, cartTotal));
        }

        if (Offer.PRODUCT.equals(f.type()) && Offer.AMOUNT.equals(f.discountMode()) && !f.productIds().isEmpty()) {
            long cheaper = products.findByIds(f.productIds()).stream().filter(p -> p.price().compareTo(f.discountValue()) < 0).count();
            if (cheaper > 0) warnings.add("The amount exceeds the price of " + cheaper + " selected product(s)");
        }
        if (Offer.GLOBAL.equals(f.type()) && Offer.AMOUNT.equals(f.discountMode()) && cartTotal != null
            && cartTotal.compareTo(f.discountValue()) < 0) {
            warnings.add("The amount is larger than this cart total: the total would be zero");
        }
        for (String w : overlapWarnings(f)) warnings.add(w);

        Map<String, Object> out = new LinkedHashMap<>();
        if (!lines.isEmpty()) {
            var r = PricingEngine.price(lines, List.of(draft), BigDecimal.ZERO);
            out.put("discountAmount", r.discountAmount());
            out.put("netTotal", r.subtotal());
        }
        out.put("warnings", warnings);
        return out;
    }

    private List<String> overlapWarnings(OfferRepository.Fields f) {
        List<String> out = new ArrayList<>();
        var page = repo.list(f.organizationId(), null, null, f.startDate(), 0, 500);
        for (Map<String, Object> o : page.items()) {
            if (!f.type().equals(o.get("type")) || !Boolean.TRUE.equals(o.get("enabled"))) continue;
            LocalDate s = LocalDate.parse((String) o.get("startDate")), en = LocalDate.parse((String) o.get("endDate"));
            if (f.endDate().isBefore(s) || f.startDate().isAfter(en)) continue;
            out.add("Offer overlaps with offer #" + o.get("id") + " (" + o.get("name") + ") on these dates");
        }
        return out;
    }
}
