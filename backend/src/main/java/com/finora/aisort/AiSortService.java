package com.finora.aisort;

import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.ResolutionIndex;
import com.finora.observability.AiSortMetrics;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.service.FynAvailabilityGuard;
import com.finora.service.FynCategorizationFallbackService;
import com.finora.service.FynCostGovernanceService;
import com.finora.service.TermsConsentService;
import com.finora.util.CounterpartyIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI sorting after an import (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md):
 * which imports get the Done step's card, and the payees a tap would sort.
 */
@Service
public class AiSortService {

    /** As InvitePromptService.MAX_IDS: one confirm makes at most a handful of statement imports. */
    public static final int MAX_IDS = 20;

    /**
     * A payee the AI may be asked about: a key that names one payee, its waiting payments out in these
     * imports whose narration may go to the model, and no saved answer yet. {@code amount} is their total.
     */
    public record SortablePayee(String counterpartyKey, List<Transaction> importRows, BigDecimal amount) {}

    /** A RUNNING run not updated for this long was cut off (a restart): it reads, and is saved, as STOPPED. */
    static final Duration STALE_AFTER = Duration.ofMinutes(10);

    /** What a tap did: the run (absent for "No thanks"), and whether it is new, so the caller starts its work. */
    public record Started(Optional<AiSortRun> run, boolean isNew) {}

    private final UserRepository users;
    private final TransactionRepository transactions;
    private final FynAvailabilityGuard guard;
    private final FynCostGovernanceService costs;
    private final FynCategorizationFallbackService fallback;
    private final AiSortRunRepository runs;
    private final TermsConsentService termsConsent;
    private final AiSortMetrics metrics;

    public AiSortService(UserRepository users, TransactionRepository transactions, FynAvailabilityGuard guard,
                         FynCostGovernanceService costs, FynCategorizationFallbackService fallback,
                         AiSortRunRepository runs, TermsConsentService termsConsent, AiSortMetrics metrics) {
        this.users = users;
        this.transactions = transactions;
        this.guard = guard;
        this.costs = costs;
        this.fallback = fallback;
        this.runs = runs;
        this.termsConsent = termsConsent;
        this.metrics = metrics;
    }

    /** The run key: the statement import ids, deduplicated, sorted, comma-joined. */
    static String importsKey(Collection<UUID> statementImportIds) {
        return statementImportIds.stream().distinct().sorted().map(UUID::toString).collect(Collectors.joining(","));
    }

    /**
     * A tap on the card. SORT is valid only while the card offers SORT; TURN_ON_AND_SORT and NO_THANKS
     * only while it asks (a customer who said no, not asked again yet) -- so the one-more question is
     * answered at most once, whatever an app sends, and two answers sent at once count once (the stamp
     * is a conditional update). Either answer stamps the question; "Turn on AI and sort" then records
     * yes exactly as the Settings switch does. A second tap for the same imports returns the run
     * already started or finished; a tap after a run stopped (the AI could not be reached, a restart,
     * the daily cap, AI turned off) tries it again, over what is still to sort.
     */
    @Transactional
    public Started start(UUID userId, AiSortDto.StartRequest request) {
        List<UUID> ids = request.statementImportIds().stream().distinct().sorted().toList();
        String key = importsKey(ids);
        Optional<AiSortRun> stopped = Optional.empty();
        if (request.choice() != AiSortDto.Choice.NO_THANKS) {
            Optional<AiSortRun> existing = runs.findByUserIdAndImportsKey(userId, key);
            if (existing.isPresent()) {
                AiSortRun run = existing.get();
                if (run.getStatus() == AiSortRun.RunStatus.RUNNING) {
                    run = readThroughCutOff(run);
                }
                if (!run.canTryAgain()) return new Started(Optional.of(run), false);
                stopped = Optional.of(run);
            }
        }
        AiSortDto.Offer offer = offer(userId, ids);
        AiSortDto.Mode needed = request.choice() == AiSortDto.Choice.SORT ? AiSortDto.Mode.SORT : AiSortDto.Mode.ASK;
        if (!offer.show() || offer.mode() != needed) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That choice is not offered for these statements.");
        }
        if (request.choice() != AiSortDto.Choice.SORT
                && users.stampAiImportQuestion(userId, Instant.now()) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That choice is not offered for these statements.");
        }
        metrics.choice(request.choice());
        if (request.choice() == AiSortDto.Choice.NO_THANKS) return new Started(Optional.empty(), false);
        if (request.choice() == AiSortDto.Choice.TURN_ON_AND_SORT) termsConsent.submit(userId, null, true);
        if (stopped.isPresent()) {
            if (runs.tryAgain(stopped.get().getId(), offer.payments(), Instant.now()) == 0) {
                // Another tap tried it again first: its run is the answer.
                return new Started(runs.findById(stopped.get().getId()), false);
            }
            return new Started(runs.findById(stopped.get().getId()), true);
        }
        AiSortRun run = runs.saveAndFlush(AiSortRun.start(userId, key, offer.payments()));
        return new Started(Optional.of(run), true);
    }

    /** A RUNNING run not updated for {@link #STALE_AFTER} was cut off (a restart): it is saved, and read, as STOPPED. */
    private AiSortRun readThroughCutOff(AiSortRun run) {
        Instant now = Instant.now();
        if (runs.stopIfQuietSince(run.getId(), now.minus(STALE_AFTER), now) == 1) {
            metrics.runFinished(AiSortRun.RunStatus.STOPPED);
            return runs.findById(run.getId()).orElseThrow();
        }
        return run;
    }

    /** The run started for these imports, if any -- the answer to a second tap that lost a race. */
    @Transactional(readOnly = true)
    public Optional<AiSortRun> existingRun(UUID userId, Collection<UUID> statementImportIds) {
        return runs.findByUserIdAndImportsKey(userId, importsKey(statementImportIds));
    }

    /**
     * A run's progress, for its owner only. A run cut off by a restart reads as STOPPED; its worker, if
     * it is in fact still going (queued behind other runs), stops before its next payee.
     */
    @Transactional
    public AiSortDto.Run run(UUID userId, UUID runId) {
        AiSortRun run = runs.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such run."));
        if (run.getStatus() == AiSortRun.RunStatus.RUNNING) run = readThroughCutOff(run);
        return AiSortDto.Run.of(run);
    }

    /**
     * Whether to show the card for {@code statementImportIds}, the statement imports one confirm just
     * made. Ids that are not the caller's simply find no rows. A customer never asked about AI (null)
     * never gets it: the question step asks them first.
     */
    @Transactional(readOnly = true)
    public AiSortDto.Offer offer(UUID userId, Collection<UUID> statementImportIds) {
        AiSortDto.Mode mode = modeFor(userId);
        if (mode == null || !guard.categorizationOffered()) return AiSortDto.Offer.none();
        List<SortablePayee> payees = sortablePayees(userId, statementImportIds);
        if (payees.isEmpty()) return AiSortDto.Offer.none();
        int payments = payees.stream().mapToInt(p -> p.importRows().size()).sum();
        BigDecimal amount = payees.stream().map(SortablePayee::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AiSortDto.Offer(true, mode, payments, amount, payees.size());
    }

    /** SORT for a yes not yet at today's cap, ASK for a no not asked again yet, else null (no card). */
    AiSortDto.Mode modeFor(UUID userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null) return null;
        Boolean consent = user.getAiProcessingConsent();
        if (Boolean.TRUE.equals(consent)) {
            return costs.userDailyCapReached(userId) ? null : AiSortDto.Mode.SORT;
        }
        if (Boolean.FALSE.equals(consent) && user.getAiImportQuestionAt() == null) return AiSortDto.Mode.ASK;
        return null;
    }

    /** The payees a tap would sort, the most money first (then by key, so the order is stable). */
    @Transactional(readOnly = true)
    public List<SortablePayee> sortablePayees(UUID userId, Collection<UUID> statementImportIds) {
        if (statementImportIds == null || statementImportIds.isEmpty()) return List.of();
        List<Transaction> waiting = transactions.findWaitingExpensesInImports(userId, statementImportIds);
        if (waiting.isEmpty()) return List.of();
        Map<String, List<Transaction>> byPayee = new LinkedHashMap<>();
        for (Transaction t : waiting) {
            String key = t.getCounterpartyKey();
            if (!CounterpartyIdentity.identifiesOnePayee(key)) continue;
            // Payments to people, and anything else the model may not see, are not counted: the AI
            // would never be asked about them.
            if (!CategorizationService.mayGoToModel(t.getDescription())) continue;
            byPayee.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        if (byPayee.isEmpty()) return List.of();
        ResolutionIndex saved = fallback.indexFor(userId);
        List<SortablePayee> out = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : byPayee.entrySet()) {
            if (fallback.suggestReadOnly(userId, e.getKey(), Transaction.Type.EXPENSE, saved).isPresent()) continue;
            BigDecimal amount = e.getValue().stream().map(t -> t.getAmount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new SortablePayee(e.getKey(), List.copyOf(e.getValue()), amount));
        }
        out.sort(Comparator.comparing(SortablePayee::amount).reversed().thenComparing(SortablePayee::counterpartyKey));
        return out;
    }
}
