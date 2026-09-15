package com.finora.service;

import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import com.finora.util.CounterpartyType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns the shared merchant corpus's write path: eligibility (spec §3), the private observation
 * log, promotion into the durable corpus (spec §4/§5), and contradiction handling (spec §7).
 */
@Service
public class SharedCorpusService {

    static final int TRUSTED_MIN_DISTINCT_VOTERS = 3;
    static final BigDecimal TRUSTED_MIN_SHARE = new BigDecimal("0.70");
    static final int TRUSTED_MIN_WINNING_VOTES = 3;
    static final double DECAY_HALF_LIFE_DAYS = 365.0;
    static final int REVALIDATION_MIN_OBSERVATIONS = 3;
    static final int REVALIDATION_MAX_DAYS = 90;

    private final CounterpartyCategoryObservationRepository observations;
    private final SharedMerchantCategoryRepository corpus;

    public SharedCorpusService(CounterpartyCategoryObservationRepository observations,
                                SharedMerchantCategoryRepository corpus) {
        this.observations = observations;
        this.corpus = corpus;
    }

    /** Spec §3's write-time invariant. Static so Task 5/6/9 can check eligibility without a
     *  service instance where that's more convenient. */
    public static boolean isEligible(String counterpartyKey, CounterpartyType counterpartyType) {
        return counterpartyKey != null && counterpartyKey.startsWith("vpa:")
                && (counterpartyType == CounterpartyType.BUSINESS
                    || counterpartyType == CounterpartyType.FINANCIAL_INSTITUTION);
    }

    /** Trusted-only, per spec §9 -- Disputed/Revalidating rows are corpus knowledge but never a
     *  suggestion. */
    public Optional<String> findTrustedSuggestion(String counterpartyKey, CounterpartyType counterpartyType,
                                                   Transaction.Type direction) {
        if (!isEligible(counterpartyKey, counterpartyType)) return Optional.empty();
        return corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction)
                .filter(row -> row.getStatus() == SharedMerchantCategory.Status.TRUSTED)
                .map(SharedMerchantCategory::getCategory);
    }

    @Transactional
    public void recordObservation(UUID userId, String counterpartyKey, CounterpartyType counterpartyType,
                                   Transaction.Type direction, String category) {
        if (!isEligible(counterpartyKey, counterpartyType)) return;

        CounterpartyCategoryObservation obs = new CounterpartyCategoryObservation();
        obs.setCounterpartyKey(counterpartyKey);
        obs.setDirection(direction);
        obs.setCategory(category);
        obs.setUserId(userId);
        obs.setCounterpartyTypeAtVote(counterpartyType);
        observations.save(obs);

        SharedMerchantCategory existing =
                corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction).orElse(null);

        if (existing != null && existing.getStatus() == SharedMerchantCategory.Status.TRUSTED
                && !category.equals(existing.getCategory())) {
            // Spec §7: a contradiction against an already-Trusted row flips it immediately,
            // regardless of the historical vote count -- deliberately NOT gated on whether the
            // full decay-weighted recompute below would actually flip the winner, which for a
            // well-established row it typically would not.
            existing.setStatus(SharedMerchantCategory.Status.REVALIDATING);
            existing.setRevalidatingSince(Instant.now());
            existing.setLastRecomputedAt(Instant.now());
            corpus.save(existing);
            return;
        }

        if (existing != null && existing.getStatus() == SharedMerchantCategory.Status.REVALIDATING
                && !revalidationWindowElapsed(existing, counterpartyKey, direction)) {
            return; // still cooling down -- stays Revalidating, no recompute yet
        }

        recomputeAndPromote(counterpartyKey, direction);
    }

    private boolean revalidationWindowElapsed(SharedMerchantCategory row, String counterpartyKey,
                                               Transaction.Type direction) {
        long daysSince = Duration.between(row.getRevalidatingSince(), Instant.now()).toDays();
        if (daysSince >= REVALIDATION_MAX_DAYS) return true;
        long countSince = observations.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(
                counterpartyKey, direction, row.getRevalidatingSince());
        return countSince >= REVALIDATION_MIN_OBSERVATIONS;
    }

    private void recomputeAndPromote(String counterpartyKey, Transaction.Type direction) {
        List<CounterpartyCategoryObservation> all =
                observations.findByCounterpartyKeyAndDirection(counterpartyKey, direction);
        Tier tier = computeTier(all);
        if (tier == null) return; // still Empty/Provisional -- fewer than 3 distinct voters

        SharedMerchantCategory row = corpus.findByCounterpartyKeyAndDirection(counterpartyKey, direction)
                .orElseGet(() -> {
                    SharedMerchantCategory fresh = new SharedMerchantCategory();
                    fresh.setCounterpartyKey(counterpartyKey);
                    fresh.setDirection(direction);
                    fresh.setPromotedAt(Instant.now());
                    return fresh;
                });
        applyTier(row, tier);
        row.setRevalidatingSince(null);
        corpus.save(row);
    }

    private static void applyTier(SharedMerchantCategory row, Tier tier) {
        row.setStatus(tier.status());
        row.setCategory(tier.category());
        row.setCategoryDistribution(tier.distribution());
        row.setDistinctUserCount(tier.distinctUserCount());
        row.setLastRecomputedAt(Instant.now());
    }

    record Tier(SharedMerchantCategory.Status status, String category,
                Map<String, BigDecimal> distribution, int distinctUserCount) {}

    /** @return null if fewer than 3 distinct voters (Empty/Provisional -- no corpus row yet). */
    static Tier computeTier(List<CounterpartyCategoryObservation> obs) {
        Set<UUID> distinctUsers = obs.stream().map(CounterpartyCategoryObservation::getUserId)
                .collect(Collectors.toSet());
        if (distinctUsers.size() < TRUSTED_MIN_DISTINCT_VOTERS) return null;

        Map<String, Long> rawCounts = obs.stream()
                .collect(Collectors.groupingBy(CounterpartyCategoryObservation::getCategory, Collectors.counting()));

        Instant now = Instant.now();
        Map<String, BigDecimal> weighted = new HashMap<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (CounterpartyCategoryObservation o : obs) {
            BigDecimal weight = decayWeight(now, o.getCreatedAt());
            weighted.merge(o.getCategory(), weight, BigDecimal::add);
            totalWeight = totalWeight.add(weight);
        }

        String winner = weighted.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
        BigDecimal winnerShare = totalWeight.signum() == 0 ? BigDecimal.ZERO
                : weighted.get(winner).divide(totalWeight, 6, RoundingMode.HALF_UP);
        long winnerRawVotes = rawCounts.getOrDefault(winner, 0L);

        Map<String, BigDecimal> distribution = new HashMap<>();
        for (var e : weighted.entrySet()) {
            distribution.put(e.getKey(), totalWeight.signum() == 0 ? BigDecimal.ZERO
                    : e.getValue().divide(totalWeight, 6, RoundingMode.HALF_UP));
        }

        boolean trusted = winnerShare.compareTo(TRUSTED_MIN_SHARE) >= 0
                && winnerRawVotes >= TRUSTED_MIN_WINNING_VOTES;
        var status = trusted ? SharedMerchantCategory.Status.TRUSTED : SharedMerchantCategory.Status.DISPUTED;
        return new Tier(status, winner, distribution, distinctUsers.size());
    }

    static BigDecimal decayWeight(Instant now, Instant createdAt) {
        double ageDays = Duration.between(createdAt, now).toDays();
        return BigDecimal.valueOf(Math.pow(0.5, ageDays / DECAY_HALF_LIFE_DAYS));
    }
}
