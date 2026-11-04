package com.waha.coupon;

import com.waha.offer.OfferClock;
import com.waha.offer.OfferRepository;
import com.waha.offer.OfferService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

// Checks coupon input from the dashboard and makes the changes. Field problems are reported per field.
// A coupon carries its own discount rule and limits; an empty limit means unlimited.
@Service
public class CouponAdminService {

    // One shape for create, edit and the batch call. count, prefix and codes are used only by the batch call.
    public record Request(String code, String name, String type, String discountMode, BigDecimal discountValue,
                          BigDecimal maxDiscountPerOrder, String storeScope, List<Long> storeIds, List<Long> productIds,
                          Boolean enabled, String startsAt, String expiresAt,
                          Integer maxInvoices, Integer maxInvoicesPerUser, BigDecimal maxAmount,
                          BigDecimal maxAmountPerUser, BigDecimal minimumOrderAmount, Boolean allowStackingWithOffers,
                          Long employeeId, Integer count, String prefix, String codes) {}

    public static final int MAX_BATCH = 500;
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,49}$");
    private static final Pattern PREFIX = Pattern.compile("^[A-Za-z0-9]{0,10}$");

    private final CouponAdminRepository repo;
    private final OfferRepository offers;
    private final OfferClock clock;
    private final TransactionTemplate tx;

    public CouponAdminService(CouponAdminRepository repo, OfferRepository offers, OfferClock clock,
                              PlatformTransactionManager txManager) {
        this.repo = repo;
        this.offers = offers;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public OfferClock clock() { return clock; }

    // existingId, existingType and existingEnabled are set when editing (the type cannot change).
    public CouponAdminRepository.Fields validate(Request r, long org, Long existingId, String existingType, Boolean existingEnabled) {
        Map<String, String> e = new LinkedHashMap<>();
        String code = r.code() == null ? "" : r.code().trim();
        if (code.isEmpty()) e.put("code", "Code is required");
        else if (!CODE.matcher(code).matches())
            e.put("code", "Code must be 2 to 50 characters: letters, digits, dot, dash or underscore");
        else if (repo.codeTaken(org, Coupon.normalize(code), existingId)) e.put("code", "This code is already used");
        var f = fields(r, code, org, existingType, existingEnabled, e);
        if (!e.isEmpty()) throw new OfferService.ValidationException(e);
        return f;
    }

    // Everything except the code. Problems are added to e.
    private CouponAdminRepository.Fields fields(Request r, String code, long org, String existingType,
                                                Boolean existingEnabled, Map<String, String> e) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.length() > 120) e.put("name", "Name must be at most 120 characters");

        String type = existingType != null ? existingType : (r.type() == null ? null : r.type().trim().toUpperCase());
        if (type == null || !(type.equals("GLOBAL") || type.equals("PRODUCT"))) {
            e.put("type", "Type must be GLOBAL or PRODUCT");
        } else if (existingType != null && r.type() != null && !r.type().trim().equalsIgnoreCase(existingType)) {
            e.put("type", "Type cannot be changed after the coupon is created");
        }

        String mode = r.discountMode() == null ? null : r.discountMode().trim().toUpperCase();
        if (mode == null || !(mode.equals("AMOUNT") || mode.equals("PERCENTAGE")))
            e.put("discountMode", "Discount mode must be AMOUNT or PERCENTAGE");
        BigDecimal value = r.discountValue();
        if (value == null || value.signum() <= 0) e.put("discountValue", "Discount value must be greater than 0");
        else if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) e.put("discountValue", "At most 2 decimals");
        else if ("PERCENTAGE".equals(mode) && value.compareTo(BigDecimal.valueOf(100)) > 0)
            e.put("discountValue", "A percentage must be at most 100");
        checkMoney(r.maxDiscountPerOrder(), "maxDiscountPerOrder", "Maximum discount", e);

        String scope = r.storeScope() == null || r.storeScope().isBlank() ? "ALL" : r.storeScope().trim().toUpperCase();
        if (!("ALL".equals(scope) || "SOME".equals(scope))) e.put("storeScope", "Store scope must be ALL or SOME");
        List<Long> storeIds = r.storeIds() == null ? List.of() : r.storeIds().stream().filter(Objects::nonNull).distinct().toList();
        if ("SOME".equals(scope)) {
            if (storeIds.isEmpty()) e.put("storeIds", "Choose at least one store");
            else if (offers.countStoresOf(org, storeIds) != storeIds.size()) e.put("storeIds", "Every store must belong to the organization");
        } else {
            storeIds = List.of();
        }
        List<Long> productIds = r.productIds() == null ? List.of() : r.productIds().stream().filter(Objects::nonNull).distinct().toList();
        if ("PRODUCT".equals(type)) {
            if (productIds.isEmpty()) e.put("productIds", "Choose at least one product");
            else if (offers.countProductsOf(org, productIds) != productIds.size())
                e.put("productIds", "Every product must belong to the organization");
        } else {
            productIds = List.of();
        }

        LocalDateTime starts = parseTime(r.startsAt(), "startsAt", e);
        LocalDateTime expires = parseTime(r.expiresAt(), "expiresAt", e);
        if (starts != null && expires != null && !expires.isAfter(starts)) e.put("expiresAt", "Expiry must be after the start");
        checkCount(r.maxInvoices(), "maxInvoices", e);
        checkCount(r.maxInvoicesPerUser(), "maxInvoicesPerUser", e);
        checkMoney(r.maxAmount(), "maxAmount", "Maximum amount", e);
        checkMoney(r.maxAmountPerUser(), "maxAmountPerUser", "Maximum amount per user", e);
        checkMoney(r.minimumOrderAmount(), "minimumOrderAmount", "Minimum order amount", e);
        if (r.employeeId() != null && !repo.employeeInOrg(r.employeeId(), org)) e.put("employeeId", "Employee not found");

        boolean enabled = r.enabled() != null ? r.enabled() : existingEnabled == null || existingEnabled;
        boolean stacking = r.allowStackingWithOffers() == null || r.allowStackingWithOffers();
        return new CouponAdminRepository.Fields(code, name.isEmpty() ? null : name, type, mode,
            value == null ? null : value.setScale(2, RoundingMode.HALF_UP), money(r.maxDiscountPerOrder()), scope,
            storeIds, productIds, enabled, starts, expires, r.maxInvoices(), r.maxInvoicesPerUser(), money(r.maxAmount()),
            money(r.maxAmountPerUser()), money(r.minimumOrderAmount()), stacking, r.employeeId());
    }

    private static void checkCount(Integer v, String field, Map<String, String> e) {
        if (v != null && v < 1) e.put(field, "Must be at least 1");
    }

    private static void checkMoney(BigDecimal v, String field, String label, Map<String, String> e) {
        if (v == null) return;
        if (v.signum() <= 0) e.put(field, label + " must be greater than 0");
        else if (v.scale() > 2 && v.stripTrailingZeros().scale() > 2) e.put(field, "At most 2 decimals");
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    // Local date-time in the offers time zone, like 2026-12-31T23:59 or 2026-12-31T23:59:59. Empty means none.
    private static LocalDateTime parseTime(String s, String field, Map<String, String> e) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim().replace(' ', 'T'));
        } catch (DateTimeParseException ex) {
            e.put(field, "Use the format yyyy-MM-ddTHH:mm");
            return null;
        }
    }

    // ── create, update ──────────────────────────────────────────────────────

    public long create(long org, CouponAdminRepository.Fields f, Long userId) {
        try {
            return tx.execute(status -> repo.insert(org, f, userId));
        } catch (DuplicateKeyException ex) {
            throw new OfferService.ValidationException(Map.of("code", "This code is already used"));
        }
    }

    public void update(long org, long id, CouponAdminRepository.Fields f, Long userId) {
        try {
            tx.executeWithoutResult(status -> repo.update(org, id, f, userId));
        } catch (DuplicateKeyException ex) {
            throw new OfferService.ValidationException(Map.of("code", "This code is already used"));
        }
    }

    // ── batch ───────────────────────────────────────────────────────────────

    public record Created(long id, String code) {}

    // Many codes with one configuration, all or nothing. The codes are either listed (codes, separated by commas,
    // spaces or new lines) or generated randomly (count and an optional prefix). A bad or taken code rejects the batch.
    public List<Created> createMany(long org, Request r, Long userId) {
        Map<String, String> e = new LinkedHashMap<>();
        boolean listed = r.codes() != null && !r.codes().isBlank();
        Set<String> codes = new LinkedHashSet<>();
        if (listed) {
            Set<String> seen = new HashSet<>();
            for (String raw : r.codes().split("[,;\\s]+")) {
                String c = raw.trim();
                if (c.isEmpty()) continue;
                if (!seen.add(Coupon.normalize(c))) { e.put("codes", "Duplicate code: " + c); break; }
                codes.add(c);
            }
            if (codes.isEmpty() && !e.containsKey("codes")) e.put("codes", "Enter at least one code");
            if (codes.size() > MAX_BATCH) e.put("codes", "At most " + MAX_BATCH + " codes per call");
            for (String c : codes) {
                if (e.containsKey("codes")) break;
                if (!CODE.matcher(c).matches())
                    e.put("codes", "Invalid code " + c + ": use 2 to 50 letters, digits, dot, dash or underscore");
                else if (repo.codeTaken(org, Coupon.normalize(c), null)) e.put("codes", "This code is already used: " + c);
            }
        } else {
            int count = r.count() == null ? 0 : r.count();
            if (count < 1 || count > MAX_BATCH) e.put("count", "Count must be between 1 and " + MAX_BATCH + ", or list the codes");
        }
        String prefix = r.prefix() == null ? "" : r.prefix().trim().toUpperCase();
        if (!listed && !PREFIX.matcher(prefix).matches()) e.put("prefix", "Prefix can have up to 10 letters or digits");
        var f = fields(r, "", org, null, null, e);
        if (!e.isEmpty()) throw new OfferService.ValidationException(e);

        if (!listed) {
            int count = r.count();
            int guard = 0;
            while (codes.size() < count) {
                if (++guard > count * 20) throw new IllegalStateException("Could not find enough unused codes");
                String code = CodeGenerator.generate(prefix);
                if (!codes.contains(code) && !repo.codeTaken(org, Coupon.normalize(code), null)) codes.add(code);
            }
        }
        try {
            return tx.execute(status -> {
                List<Created> out = new ArrayList<>();
                for (String code : codes) {
                    var one = new CouponAdminRepository.Fields(code, f.name(), f.type(), f.discountMode(), f.discountValue(),
                        f.maxDiscountPerOrder(), f.storeScope(), f.storeIds(), f.productIds(), f.enabled(), f.startsAt(),
                        f.expiresAt(), f.maxInvoices(), f.maxInvoicesPerUser(), f.maxAmount(), f.maxAmountPerUser(),
                        f.minimumOrderAmount(), f.allowStackingWithOffers(), f.employeeId());
                    long id = repo.insert(org, one, userId);
                    repo.audit(id, userId, "CREATE", null, Map.of("code", code, "batch", true));
                    out.add(new Created(id, code));
                }
                return out;
            });
        } catch (DuplicateKeyException ex) {
            throw new OfferService.ValidationException(Map.of("codes", "One of the codes is already used"));
        }
    }
}
