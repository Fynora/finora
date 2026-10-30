package com.finora.service;

import com.finora.dto.BillingOfferAdminDtos.BillingOfferAdminDto;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferCreateRequest;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferPlanOptionDto;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferState;
import com.finora.entity.BillingOffer;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.exception.ApiException;
import com.finora.notification.campaign.IstClock;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The admin side of billing offers (V297): list every offer, create one, and end one now. Checkout
 * and the pricing pages read offers through {@link BillingOfferService}; this class only writes.
 *
 * <h2>Rules for a new offer</h2>
 * <ul>
 *   <li>The plan and cycle have an active price with a Razorpay plan id: checkout needs that id to
 *       create the subscription the offer price rides on.</li>
 *   <li>The price is in whole paise, at least {@link #MIN_PRICE} (Razorpay's minimum payment, 100
 *       paise), and below the regular price. {@link BillingOfferService} skips an offer that is not
 *       below it, so saving one would show an offer nobody gets.</li>
 *   <li>The window is [startsAt, endsAt), end after start, end still in the future, and start no
 *       more than {@link #START_GRACE} in the past. A start further back would record the offer as
 *       live at a time it was not, and the table is the record of past offers.</li>
 *   <li>No two offers on the same plan and cycle overlap: checkout would pick the cheaper silently.
 *       To replace a live offer, end it first. Back-to-back windows are fine.</li>
 * </ul>
 *
 * <p>Nothing is ever deleted. "End now" sets the end to now; for an offer that has not started yet,
 * it also moves the start to one microsecond before (the table requires end after start), so the
 * row reads as ended and was never live at any time a checkout could have seen. The original window
 * is kept in the audit entry. Every create and end is written to the audit log with the acting admin.
 */
@Service
public class BillingOfferAdminService {

    /** Razorpay's minimum payment amount is 100 paise. */
    static final BigDecimal MIN_PRICE = new BigDecimal("1.00");

    /** How far in the past a start may be, so "start now" survives the time it takes to submit. */
    static final Duration START_GRACE = Duration.ofMinutes(5);

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,39}");

    private static final DateTimeFormatter IST_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'", Locale.ENGLISH).withZone(BillingCalendar.ZONE);

    private final BillingOfferRepository offerRepository;
    private final BillingPriceRepository priceRepository;
    private final PlanRepository planRepository;
    private final BillingOfferService offerService;
    private final AuditService auditService;
    private final IstClock clock;

    public BillingOfferAdminService(BillingOfferRepository offerRepository, BillingPriceRepository priceRepository,
                                    PlanRepository planRepository, BillingOfferService offerService,
                                    AuditService auditService, IstClock clock) {
        this.offerRepository = offerRepository;
        this.priceRepository = priceRepository;
        this.planRepository = planRepository;
        this.offerService = offerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Every offer, newest start first. */
    @Transactional(readOnly = true)
    public List<BillingOfferAdminDto> list() {
        Instant now = clock.now();
        Map<UUID, Plan> plans = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));
        Map<String, BillingPrice> prices = priceRepository.findByActiveTrue().stream()
                .collect(Collectors.toMap(p -> key(p.getPlanId(), p.getBillingCycle()), Function.identity(), (a, b) -> a));
        Map<String, Optional<UUID>> applied = new HashMap<>();
        return offerRepository.findAllByOrderByStartsAtDescCreatedAtDesc().stream()
                .map(o -> {
                    Optional<UUID> appliedId = applied.computeIfAbsent(key(o.getPlanId(), o.getBillingCycle()),
                            k -> offerService.liveOffer(o.getPlanId(), o.getBillingCycle(), now).map(BillingOffer::getId));
                    return toDto(o, plans.get(o.getPlanId()), prices.get(key(o.getPlanId(), o.getBillingCycle())),
                            now, appliedId.filter(o.getId()::equals).isPresent());
                })
                .toList();
    }

    /** The paid plans and cycles an offer can be made for, cheapest first. */
    @Transactional(readOnly = true)
    public List<BillingOfferPlanOptionDto> planOptions() {
        Map<UUID, Plan> plans = planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity()));
        return priceRepository.findByActiveTrue().stream()
                .filter(p -> p.getPrice() != null && p.getPrice().signum() > 0)
                .filter(p -> plans.containsKey(p.getPlanId()) && plans.get(p.getPlanId()).isActive())
                .sorted(Comparator.comparing(BillingPrice::getPrice))
                .map(p -> {
                    Plan plan = plans.get(p.getPlanId());
                    return new BillingOfferPlanOptionDto(plan.getCode(), plan.getName(), p.getBillingCycle(),
                            p.getPrice(), p.getRazorpayPlanId() != null);
                })
                .toList();
    }

    @Transactional
    public BillingOfferAdminDto create(UUID adminUserId, BillingOfferCreateRequest request) {
        Instant now = clock.now();
        String code = request.code().strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw bad("The code must be 3 to 40 capital letters, digits, \"_\" or \"-\", for example HOLI2027.");
        }
        String cycle = request.billingCycle().strip().toUpperCase(Locale.ROOT);
        if (!BillingPrice.CYCLE_MONTHLY.equals(cycle) && !BillingPrice.CYCLE_YEARLY.equals(cycle)) {
            throw bad("The billing cycle must be MONTHLY or YEARLY.");
        }
        Plan plan = planRepository.findByCode(request.planCode().strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> bad("Unknown plan \"" + request.planCode() + "\"."));
        String label = request.label().strip();
        if (label.isEmpty()) {
            throw bad("Give the offer a name customers will see, for example \"Holi offer\".");
        }
        if (label.chars().anyMatch(Character::isISOControl)) {
            throw bad("The name contains characters that cannot be saved.");
        }

        // Locked: a second admin creating an offer for this plan and cycle waits here, then sees
        // this one in the overlap check below.
        BillingPrice regular = priceRepository.lockActive(plan.getId(), cycle)
                .orElseThrow(() -> bad(plan.getName() + " has no active " + cycle.toLowerCase(Locale.ROOT)
                        + " price, so there is nothing to discount."));
        if (regular.getRazorpayPlanId() == null) {
            throw bad(plan.getName() + " " + cycle.toLowerCase(Locale.ROOT)
                    + " is not set up for checkout yet (it has no Razorpay plan id), so no one could buy the offer.");
        }

        BigDecimal price = request.firstPeriodPrice();
        if (price.stripTrailingZeros().scale() > 2) {
            throw bad("The price can have at most two decimal places (whole paise).");
        }
        if (price.compareTo(MIN_PRICE) < 0) {
            throw bad("The offer price must be at least Rs 1, the smallest amount Razorpay can charge.");
        }
        if (price.compareTo(regular.getPrice()) >= 0) {
            throw bad("The offer price must be below the regular price of Rs " + regular.getPrice().toPlainString()
                    + ", or it is not an offer.");
        }

        Instant startsAt = request.startsAt().truncatedTo(ChronoUnit.MICROS);
        Instant endsAt = request.endsAt().truncatedTo(ChronoUnit.MICROS);
        if (!endsAt.isAfter(startsAt)) {
            throw bad("The end must be after the start.");
        }
        if (!endsAt.isAfter(now)) {
            throw bad("The end has already passed, so this offer would never be live. Pick a later end.");
        }
        if (startsAt.isBefore(now.minus(START_GRACE))) {
            throw bad("The start is in the past. Start the offer now or later: past offers are a record of what "
                    + "customers were actually offered.");
        }

        List<BillingOffer> clashes = offerRepository.findOverlapping(plan.getId(), cycle, startsAt, endsAt);
        if (!clashes.isEmpty()) {
            BillingOffer other = clashes.get(0);
            throw new ApiException(HttpStatus.CONFLICT, String.format(
                    "This overlaps \"%s\" (%s, %s to %s) on %s %s. End that offer first, or change the dates.",
                    other.getLabel(), other.getCode(), IST_FORMAT.format(other.getStartsAt()),
                    IST_FORMAT.format(other.getEndsAt()), plan.getName(), cycle.toLowerCase(Locale.ROOT)));
        }

        if (offerRepository.existsByCodeAndPlanIdAndBillingCycle(code, plan.getId(), cycle)) {
            throw codeTaken(code, plan, cycle);
        }

        BillingOffer offer = new BillingOffer();
        offer.setCode(code);
        offer.setPlanId(plan.getId());
        offer.setBillingCycle(cycle);
        offer.setFirstPeriodPrice(price.setScale(2));
        offer.setLabel(label);
        offer.setStartsAt(startsAt);
        offer.setEndsAt(endsAt);
        BillingOffer saved;
        try {
            saved = offerRepository.saveAndFlush(offer);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE (code, plan_id, billing_cycle), reached only when a concurrent save won the race.
            throw codeTaken(code, plan, cycle);
        }

        auditService.record(adminUserId, "BILLING_OFFER_CREATED", "BillingOffer", saved.getId(), describe(saved, plan));
        return toDto(saved, plan, regular, now, saved.isLiveAt(now));
    }

    /** Ends an offer now. Refused for an offer that has already ended. */
    @Transactional
    public BillingOfferAdminDto endNow(UUID adminUserId, UUID id) {
        BillingOffer offer = offerRepository.lockById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Offer not found: " + id));
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        if (!offer.getEndsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This offer already ended at " + IST_FORMAT.format(offer.getEndsAt()) + ".");
        }
        Plan plan = planRepository.findById(offer.getPlanId()).orElse(null);
        Map<String, Object> before = describe(offer, plan);
        boolean started = offer.getStartsAt().isBefore(now);

        offer.setEndsAt(now);
        if (!started) {
            offer.setStartsAt(now.minus(1, ChronoUnit.MICROS));
        }
        BillingOffer saved = offerRepository.saveAndFlush(offer);

        Map<String, Object> metadata = new LinkedHashMap<>(describe(saved, plan));
        metadata.put("before", before);
        metadata.put("endedBeforeStart", !started);
        auditService.record(adminUserId, "BILLING_OFFER_ENDED", "BillingOffer", saved.getId(), metadata);
        BillingPrice regular = priceRepository.findByPlanIdAndBillingCycleAndActiveTrue(offer.getPlanId(),
                offer.getBillingCycle()).orElse(null);
        return toDto(saved, plan, regular, now, false);
    }

    // --- internals ---

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException codeTaken(String code, Plan plan, String cycle) {
        return new ApiException(HttpStatus.CONFLICT, "The code " + code + " was already used for " + plan.getName()
                + " " + cycle.toLowerCase(Locale.ROOT) + ". Pick a new code, for example with the year in it.");
    }

    private static String key(UUID planId, String cycle) {
        return planId + "/" + cycle;
    }

    static BillingOfferState stateAt(BillingOffer offer, Instant now) {
        if (now.isBefore(offer.getStartsAt())) return BillingOfferState.UPCOMING;
        if (now.isBefore(offer.getEndsAt())) return BillingOfferState.LIVE;
        return BillingOfferState.ENDED;
    }

    private static Map<String, Object> describe(BillingOffer offer, Plan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", offer.getCode());
        m.put("planCode", plan != null ? plan.getCode() : offer.getPlanId().toString());
        m.put("billingCycle", offer.getBillingCycle());
        m.put("firstPeriodPrice", offer.getFirstPeriodPrice().toPlainString());
        m.put("label", offer.getLabel());
        m.put("startsAt", offer.getStartsAt().toString());
        m.put("endsAt", offer.getEndsAt().toString());
        return m;
    }

    private static BillingOfferAdminDto toDto(BillingOffer o, Plan plan, BillingPrice regular, Instant now,
                                              boolean appliedAtCheckout) {
        return new BillingOfferAdminDto(o.getId(), o.getCode(),
                plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null,
                o.getBillingCycle(), o.getFirstPeriodPrice(), regular != null ? regular.getPrice() : null,
                o.getLabel(), o.getStartsAt(), o.getEndsAt(), stateAt(o, now), appliedAtCheckout, o.getCreatedAt());
    }
}
