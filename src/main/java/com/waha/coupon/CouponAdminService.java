package com.waha.coupon;

import com.waha.offer.Offer;
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
@Service
public class CouponAdminService {

    public record Request(String code, Long offerId, Boolean enabled, String startsAt, String expiresAt,
                          Integer usageLimit, BigDecimal minimumOrderAmount, BigDecimal maxDiscountPerOrder,
                          Long employeeId) {}

    public record GenerateRequest(Long offerId, Integer count, String prefix, String startsAt, String expiresAt,
                                  Integer usageLimit, BigDecimal minimumOrderAmount, BigDecimal maxDiscountPerOrder,
                                  Long employeeId) {}

    public static final int MAX_GENERATE = 500;
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

    // existingId, existingOfferId and existingEnabled are set when editing.
    public CouponAdminRepository.Fields validate(Request r, long org, Long existingId, Long existingOfferId, Boolean existingEnabled) {
        Map<String, String> e = new LinkedHashMap<>();

        String code = r.code() == null ? "" : r.code().trim();
        if (code.isEmpty()) e.put("code", "Code is required");
        else if (!CODE.matcher(code).matches())
            e.put("code", "Code must be 2 to 50 characters: letters, digits, dot, dash or underscore");
        else if (repo.codeTaken(org, Coupon.normalize(code), existingId)) e.put("code", "This code is already used");

        checkOffer(r.offerId(), org, e);
        if (existingId != null && r.offerId() != null && !r.offerId().equals(existingOfferId) && repo.hasRedemptions(existingId))
            e.put("offerId", "The linked offer cannot change after the coupon has been used");

        LocalDateTime starts = parseTime(r.startsAt(), "startsAt", e);
        LocalDateTime expires = parseTime(r.expiresAt(), "expiresAt", e);
        checkRules(starts, expires, r.usageLimit(), r.minimumOrderAmount(), r.maxDiscountPerOrder(), r.employeeId(), org, e);

        if (!e.isEmpty()) throw new OfferService.ValidationException(e);
        boolean enabled = r.enabled() != null ? r.enabled() : existingEnabled == null || existingEnabled;
        return new CouponAdminRepository.Fields(code, r.offerId(), enabled, starts, expires, r.usageLimit(),
            money(r.minimumOrderAmount()), money(r.maxDiscountPerOrder()), r.employeeId());
    }

    private void checkOffer(Long offerId, long org, Map<String, String> e) {
        if (offerId == null) { e.put("offerId", "A linked offer is required"); return; }
        Offer offer = offers.loadById(offerId).orElse(null);
        if (offer == null || offer.organizationId() != org) e.put("offerId", "Offer not found");
        else if (!Offer.COUPON.equals(offer.activationMode())) e.put("offerId", "The offer must have activation mode COUPON");
    }

    private void checkRules(LocalDateTime starts, LocalDateTime expires, Integer limit, BigDecimal min, BigDecimal max,
                            Long employeeId, long org, Map<String, String> e) {
        if (starts != null && expires != null && !expires.isAfter(starts)) e.put("expiresAt", "Expiry must be after the start");
        if (limit != null && limit < 1) e.put("usageLimit", "Usage limit must be at least 1");
        checkMoney(min, "minimumOrderAmount", "Minimum order amount", e);
        checkMoney(max, "maxDiscountPerOrder", "Maximum discount", e);
        if (employeeId != null && !repo.employeeInOrg(employeeId, org)) e.put("employeeId", "Employee not found");
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
            return repo.insert(org, f, userId);
        } catch (DuplicateKeyException ex) {
            throw new OfferService.ValidationException(Map.of("code", "This code is already used"));
        }
    }

    public void update(long org, long id, CouponAdminRepository.Fields f, Long userId) {
        try {
            repo.update(org, id, f, userId);
        } catch (DuplicateKeyException ex) {
            throw new OfferService.ValidationException(Map.of("code", "This code is already used"));
        }
    }

    // ── generate ────────────────────────────────────────────────────────────

    public record Created(long id, String code) {}

    // count random codes of one offer with the same rules, all or nothing.
    public List<Created> generate(long org, GenerateRequest r, Long userId) {
        Map<String, String> e = new LinkedHashMap<>();
        int count = r.count() == null ? 0 : r.count();
        if (count < 1 || count > MAX_GENERATE) e.put("count", "Count must be between 1 and " + MAX_GENERATE);
        String prefix = r.prefix() == null ? "" : r.prefix().trim().toUpperCase();
        if (!PREFIX.matcher(prefix).matches()) e.put("prefix", "Prefix can have up to 10 letters or digits");
        checkOffer(r.offerId(), org, e);
        LocalDateTime starts = parseTime(r.startsAt(), "startsAt", e);
        LocalDateTime expires = parseTime(r.expiresAt(), "expiresAt", e);
        checkRules(starts, expires, r.usageLimit(), r.minimumOrderAmount(), r.maxDiscountPerOrder(), r.employeeId(), org, e);
        if (!e.isEmpty()) throw new OfferService.ValidationException(e);

        Set<String> codes = new LinkedHashSet<>();
        int guard = 0;
        while (codes.size() < count) {
            if (++guard > count * 20) throw new IllegalStateException("Could not find enough unused codes");
            String code = CodeGenerator.generate(prefix);
            if (!codes.contains(code) && !repo.codeTaken(org, Coupon.normalize(code), null)) codes.add(code);
        }
        return tx.execute(status -> {
            List<Created> out = new ArrayList<>();
            for (String code : codes) {
                var f = new CouponAdminRepository.Fields(code, r.offerId(), true, starts, expires, r.usageLimit(),
                    money(r.minimumOrderAmount()), money(r.maxDiscountPerOrder()), r.employeeId());
                long id = repo.insert(org, f, userId);
                repo.audit(id, userId, "CREATE", null, Map.of("code", code, "offerId", r.offerId(), "generated", true));
                out.add(new Created(id, code));
            }
            return out;
        });
    }
}
