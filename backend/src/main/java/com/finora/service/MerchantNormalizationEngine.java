package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.util.CategoryRules;
import com.finora.util.PaymentRailTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a raw transaction description to a single canonical Merchant, grouping variants
 * like "AMAZON SELLER SERVICES", "Amazon Pay", and "Amazon Marketplace" under one identity.
 *
 * Exact alias matching (same normalized description seen before) always wins first. For a
 * genuinely new description there are two matches, in this order (see {@link #groupingKey}):
 *
 * <ol>
 *   <li>the FIRST TWO significant tokens of the payee, against every merchant's canonical name
 *       reduced the same way -- "rajesh tea" for "UPI/.../RAJESH TEA CAFE";</li>
 *   <li>the first significant token alone, but only against an APPROVED merchant whose canonical
 *       name is that one word -- the curated brands {@link MerchantSeedService} gives every user
 *       ("amazon" from all three examples above), or a merchant a person has confirmed.</li>
 * </ol>
 *
 * A deliberately simple heuristic, not fuzzy matching or NLP. Two spellings of an unseeded brand
 * that differ in their second word ("zepto" and "zepto marketplace") stay two merchants; that is
 * what the manual "merge merchants" feature exists to fix by hand, and it is the failure this
 * class can afford. The opposite failure -- two unrelated payees pooled into one merchant -- is
 * not, because a merchant's learned category is then applied to both. See {@link #groupingKey}
 * for what the first-token-only key it replaced did on the real corpus.
 *
 * <h2>The N+1 that used to be here, and why the decision to keep it was reversed (Bug 35)</h2>
 *
 * Any description whose exact spelling has not been seen before falls through to a match against
 * the user's whole merchant table. That load used to happen once per ROW — real bank descriptions
 * carry a per-transaction reference ("SWIGGY ORDER 4471"), so nearly every row is a new alias even
 * when it is the same few merchants repeatedly, and the cost scaled with rows × the user's
 * lifetime merchant count. Measured in {@link MerchantNormalizationEngineTest}: a 500-row
 * statement with 50 distinct merchants performed <b>500 full merchant-table loads</b>.
 *
 * <p>This class previously recorded four candidate fixes, all rejected, and the reasoning is worth
 * keeping because three of those rejections still stand:
 *
 * <ol>
 *   <li><b>Snapshot the merchant list once per import.</b> Still wrong, and the trap the
 *       implementation below has to avoid. {@code resolve()} CREATES merchants as it goes, so row
 *       3 would not see the merchant row 1 created and would make its own — three "Swiggy" rows
 *       instead of one, splitting the user's spend and splitting what the learning engine is
 *       taught. That is a silent data-quality bug, far worse than the latency it saves. Guarded by
 *       {@code differentSpellingsOfANewMerchantCollapseOntoOne} and
 *       {@code aMerchantCreatedMidTransactionIsSeenByLaterRows}.</li>
 *
 *   <li><b>Transaction-scoped cache.</b> <b>This is now what the code does</b> — see
 *       {@link #merchantsByFirstToken}. It was rejected on two grounds and both have since failed:
 *       <ul>
 *         <li>"changes the signature every caller uses" — it does not have to. The memo lives
 *             inside this class, keyed on the transaction, so no caller signature changed and the
 *             dry-run-flag objection that rules out threading a context object does not apply.</li>
 *         <li>"to fix a sub-second cost" — that premise was wrong. End-to-end measurement found
 *             realistic statement sizes taking <i>minutes</i>, with permitted 10 MB uploads never
 *             completing at all. The cost was never sub-second on a real account; it was
 *             sub-second on a test fixture with almost no merchant history.</li>
 *       </ul>
 *       500 loads became 1, with no behavioural change: same rows, same comparison, same first
 *       winner.</li>
 *
 *   <li><b>Persist the normalised grouping key as an indexed column.</b> Still the most principled
 *       fix and still not done, for the reason recorded before: the key is computed by
 *       {@code CategoryRules.normalize} + {@code groupingKey} in Java, so the column
 *       needs a backfill reproducing that logic in SQL exactly, plus a standing obligation to
 *       recompute it wherever a canonical name changes (admin rename and merge). A backfill that
 *       is subtly different silently stops matching merchants that used to match. Revisit if one
 *       load per import per user ever becomes the bottleneck; it is not today.</li>
 *
 *   <li><b>Read a two-column projection.</b> Still rejected. Built and measured once, then
 *       reverted: it leaves the query count unchanged and <i>adds</i> a {@code findById} per token
 *       match — measured at +450 lookups on a 500-row import. Moot now that the count is 1.</li>
 * </ol>
 *
 * What was fixed, because it was an outright waste rather than a trade-off:
 * {@code ImportService} called {@code findByUserId(userId).size()} twice per import purely to
 * report how many merchants were newly learned, hydrating the entire table to produce a number the
 * database returns directly. That is now {@code countByUserId}.
 */
@Service
public class MerchantNormalizationEngine {

    private static final Logger log = LoggerFactory.getLogger(MerchantNormalizationEngine.class);

    private final MerchantRepository merchantRepository;
    private final MerchantAliasRepository merchantAliasRepository;

    public MerchantNormalizationEngine(MerchantRepository merchantRepository, MerchantAliasRepository merchantAliasRepository) {
        this.merchantRepository = merchantRepository;
        this.merchantAliasRepository = merchantAliasRepository;
    }

    /**
     * The widest value {@code merchant_aliases.normalized_alias} and {@code merchants.canonical_name}
     * can hold (V7). Both are {@code VARCHAR(255)}, and both are written from parser output.
     */
    private static final int MAX_STORED_LENGTH = 255;

    /** Transaction-scoped key for the per-user merchant memo -- see merchantsByFirstToken. */
    private static final String MEMO_KEY = MerchantNormalizationEngine.class.getName() + ".merchantMemo";

    @Transactional
    public Merchant resolve(UUID userId, String description) {
        String rawAlias = CategoryRules.normalize(description);

        // Cut it to size before the database refuses it. A real credit-card statement produced a
        // 400-character "merchant" -- one narration that had absorbed a page of cheque
        // instructions -- and the VARCHAR(255) insert aborted the JDBC batch. That marks the whole
        // transaction rollback-only, so the import died later with UnexpectedRollbackException: an
        // HTTP 500, for a document the parser had merely misread.
        //
        // Catching it downstream cannot help, which is the crux. By the time the constraint fires
        // the transaction is already poisoned, and no handling un-poisons it. The write simply
        // must not be attempted.
        //
        // Truncating rather than skipping, because every caller dereferences this result -- a null
        // would trade a rollback for an NPE, the same 500 by another route. And truncation loses
        // less than it looks: the boilerplate is APPENDED, so the first 255 characters still carry
        // the real narration. It also stays deterministic, so the same misparsed description maps
        // to the same merchant on re-import instead of multiplying rows.
        if (rawAlias.length() > MAX_STORED_LENGTH) {
            log.warn("Truncating a {}-character description to {} for merchant resolution. This is "
                    + "a PARSER fault, not a data fault: a narration that long means row "
                    + "segmentation absorbed surrounding page text.",
                    rawAlias.length(), MAX_STORED_LENGTH);
        }
        // A separate final, not a reassignment: normalizedAlias is captured by the lambda below,
        // and reassigning it makes it no longer effectively final.
        final String normalizedAlias = fitToColumn(rawAlias);

        var existingAlias = merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias);
        if (existingAlias.isPresent()) {
            // findByIdAndUserId, not a bare findById. This is safe either way -- the id comes from
            // an alias row already scoped to this user -- but MerchantRepository's own comment
            // states the rule as "never a bare findById", and a rule with one silent exception is
            // a rule the next reader cannot trust. Same query cost, no exception to explain.
            var byAlias = merchantRepository.findByIdAndUserId(existingAlias.get().getMerchantId(), userId);
            if (byAlias.isPresent()) return byAlias.get();
            // Bug 56: a dangling alias -- its target merchant is gone (e.g.
            // MerchantReviewService.discard() removes a merchant with no attached transactions but,
            // unlike MerchantService.merge(), never touches its alias rows). createMerchantAndAlias
            // would not have fixed this: its addAlias() is an INSERT ... ON CONFLICT DO NOTHING
            // against (user_id, normalized_alias), which already has a row here -- just pointing at
            // a dead merchant -- so the insert silently no-ops and the dangling row is left exactly
            // as broken as it was. Every future call for this same alias would repeat this path and
            // spawn a fresh, never-linked duplicate merchant, forever. Repointing the EXISTING row
            // at a fresh merchant instead is the same repair MerchantService.merge() already does
            // when it repoints an absorbed merchant's aliases onto the surviving one.
            return repointDanglingAlias(userId, description, existingAlias.get());
        }

        // payeeText, not the raw normalised alias -- see groupingKey for why the two sides of this
        // comparison must be reduced by the same rule, and what breaks when they are not.
        var candidate = merchantsByGroupingKey(userId).match(payeeText(description));
        if (candidate != null) {
            addAlias(candidate.getId(), userId, normalizedAlias);
            return candidate;
        }

        Merchant created = createMerchantAndAlias(userId, description, normalizedAlias);
        // Keep the memo consistent with what this transaction has now written. Without this, two
        // rows in the same statement naming the same new merchant would each miss the memo, each
        // create a merchant, and the second would collide on the alias index -- turning the fix
        // into a new source of the exact failure addAlias() exists to survive.
        rememberInMemo(userId, created);
        return created;
    }

    /**
     * The user's merchants indexed for {@link Grouping#match}, loaded at most once per transaction.
     *
     * <p><b>Bug 35.</b> This lookup used to be
     * {@code merchantRepository.findByUserId(userId).stream().filter(...)} — a full load of every
     * merchant the user owns, executed inside a per-ROW call. {@code resolve} runs once per staged
     * row and this branch is taken on every alias miss, which is the common case on a first import
     * rather than an edge case. A 300-row statement therefore performed up to 300 full merchant
     * loads, and the cost grew with the user's history rather than with the size of the import.
     * The end-to-end symptom was measured independently: realistic statement sizes taking minutes,
     * and permitted 10 MB uploads never completing.
     *
     * <p>Memoized per transaction rather than indexed by a stored column. An indexed lookup would
     * be faster still, but the key is computed by {@code CategoryRules.normalize} +
     * {@link #groupingKey} in Java, so a column would need a backfill that reproduces that logic in
     * SQL exactly — and a backfill that is subtly different silently stops matching merchants that
     * used to match. Memoizing changes no behaviour at all: the same rows, the same comparison, the
     * same first winner. It only stops asking the database the identical question once per row.
     *
     * <p>Scoped to the transaction, not to the bean, deliberately. A longer-lived cache would go
     * stale against other users' writes and would need invalidation this class has no way to
     * observe; a transaction is exactly the window in which the answer cannot change underneath us
     * except by our own writes, which {@link #rememberInMemo} accounts for. Outside a transaction
     * the memo is skipped entirely and the old behaviour stands, so nothing depends on callers
     * being transactional.
     *
     * <p>First match wins, matching the previous {@code findFirst()} on an unordered query. The
     * memo therefore keeps the FIRST merchant seen for a key rather than the last.
     */
    private Grouping merchantsByGroupingKey(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return Grouping.of(merchantRepository.findByUserId(userId));
        }

        @SuppressWarnings("unchecked")
        Map<UUID, Grouping> perUser = (Map<UUID, Grouping>) TransactionSynchronizationManager.getResource(MEMO_KEY);
        if (perUser == null) {
            perUser = new HashMap<>();
            TransactionSynchronizationManager.bindResource(MEMO_KEY, perUser);
            // Unbind when the transaction ends, or the resource leaks onto whatever this thread
            // serves next -- which for a request thread is another user's request.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(MEMO_KEY);
                }
            });
        }
        return perUser.computeIfAbsent(userId, id -> Grouping.of(merchantRepository.findByUserId(id)));
    }

    private void rememberInMemo(UUID userId, Merchant created) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        @SuppressWarnings("unchecked")
        Map<UUID, Grouping> perUser = (Map<UUID, Grouping>) TransactionSynchronizationManager.getResource(MEMO_KEY);
        if (perUser == null) return;
        Grouping grouping = perUser.get(userId);
        if (grouping != null) grouping.add(created);
    }

    /**
     * A user's merchants keyed both ways {@link MerchantNormalizationEngine#match} looks them up.
     * {@link MerchantNormalizationEngine#indexFor} builds the same thing for a staging pass, so staging and confirm reduce
     * canonical names by one rule and cannot disagree about which merchant a row belongs to.
     */
    private static final class Grouping {
        private final Map<String, Merchant> byKey = new HashMap<>();
        private final Map<String, Merchant> byBrand = new HashMap<>();

        static Grouping of(List<Merchant> merchants) {
            Grouping grouping = new Grouping();
            merchants.forEach(grouping::add);
            return grouping;
        }

        void add(Merchant merchant) {
            String reduced = CategoryRules.normalize(merchant.getCanonicalName());
            String key = groupingKey(reduced);
            // putIfAbsent: first match wins, which is what findFirst() did.
            if (key != null) byKey.putIfAbsent(key, merchant);
            String brand = brandToken(merchant, reduced);
            if (brand != null) byBrand.putIfAbsent(brand, merchant);
        }

        Merchant match(String extractedMerchant) {
            return MerchantNormalizationEngine.match(extractedMerchant, byKey::get, byBrand::get);
        }
    }

    /**
     * The one matching rule, shared by the memo above and {@link com.finora.imports.MerchantIndex}:
     * the two-token {@link #groupingKey} first, then the first significant token against a
     * one-word APPROVED merchant (see {@link #brandToken} for why only those).
     */
    private static Merchant match(String extractedMerchant,
                                  java.util.function.Function<String, Merchant> byKey,
                                  java.util.function.Function<String, Merchant> byBrand) {
        String key = groupingKey(extractedMerchant);
        if (key == null) return null;
        Merchant exact = byKey.apply(key);
        if (exact != null) return exact;
        List<String> significant = significantTokens(extractedMerchant);
        return significant.isEmpty() ? null : byBrand.apply(significant.get(0));
    }

    /**
     * The one word a merchant answers to on its own, or null when it answers only to its full
     * {@link #groupingKey}.
     *
     * <p>Only an APPROVED merchant whose canonical name is a single significant word qualifies:
     * the curated brands {@link MerchantSeedService} gives every user ("Swiggy", "Zepto"), or a
     * merchant a person has approved, renamed or merged in the Merchant Review Center. A brand's
     * narrations rarely agree past the first word -- "SWIGGY BANGALORE", "SWIGGY INSTAMART",
     * "ZEPTO MARKETPLACE PR" -- and these merchants exist precisely to catch all of them.
     *
     * <p>A TEMPORARY merchant never qualifies, even when its name is one word. The engine created
     * it from one narration, and one word of a narration is exactly the key that pooled unrelated
     * payees before (see {@link #groupingKey}). On the real corpus one row's payee reduced to the
     * single common word that a clearing house, a railway ticket counter and a food stall share; a
     * TEMPORARY merchant named that word would have absorbed all three again.
     */
    private static String brandToken(Merchant merchant, String reducedCanonicalName) {
        if (merchant.getLifecycleStatus() != Merchant.Lifecycle.APPROVED) return null;
        List<String> significant = significantTokens(reducedCanonicalName);
        return significant.size() == 1 ? significant.get(0) : null;
    }

    /**
     * Resolves a description to an existing merchant WITHOUT creating or writing anything (WI3).
     *
     * <p>Same matching as {@link #resolve} — exact alias first, then the grouping key — and
     * deliberately the same order, so a staged preview shows the merchant a confirm would actually
     * pick. What it does not do is the three writes {@code resolve} performs on a miss: no
     * {@code Merchant} row, no {@code MerchantAlias} row, no alias added to a token match.
     *
     * <p>This is what makes staging read-only, and it closes Bug 36. Staging is a PREVIEW the user
     * may abandon, and it used to leave a permanent merchant behind for every distinct description
     * in a file that was never imported — visible in their Merchants page, in
     * {@code WorkspaceDashboardService}'s totals and in the admin's platform-wide counts, all
     * derived from transactions that do not exist.
     *
     * <p>Returning empty on a miss is correct rather than a limitation: at staging time there IS no
     * merchant yet, and {@code StagedRow} carries no merchant field, so nothing downstream needs
     * one. The merchant is created at confirm time, by the path that also creates the transaction
     * it belongs to.
     *
     * <p><b>Costs one full merchant load (via {@link #merchantsByFirstToken}) per call unless the
     * caller is inside an active transaction</b> -- {@link #merchantsByFirstToken}'s memo, like
     * {@code resolve()}'s, is scoped to the transaction, and this method has no transaction of its
     * own to offer one ({@code readOnly = true} still opens and immediately commits one PER CALL
     * when nothing higher up the stack is already transactional). Fine for a caller invoking this a
     * handful of times; wrong for once per row of a statement with no enclosing transaction, which
     * is exactly {@code TransactionNormalizer.normalize}'s staging use. That caller must use
     * {@link #indexFor} plus the {@link #resolveReadOnly(UUID, String, MerchantIndex)} overload
     * instead -- see {@link com.finora.imports.MerchantIndex}'s own doc comment for the regression
     * this fixes.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Merchant> resolveReadOnly(UUID userId, String description) {
        String normalizedAlias = fitToColumn(CategoryRules.normalize(description));

        var existingAlias = merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias);
        if (existingAlias.isPresent()) {
            var byAlias = merchantRepository.findByIdAndUserId(existingAlias.get().getMerchantId(), userId);
            if (byAlias.isPresent()) return byAlias;
        }

        // Identical reduction to resolve()'s, deliberately. This method exists so a staged preview
        // shows the merchant a confirm would actually pick; tokenising different text here than
        // resolve() does would break exactly that guarantee.
        return java.util.Optional.ofNullable(
                merchantsByGroupingKey(userId).match(payeeText(description)));
    }

    /**
     * A snapshot of every merchant and alias this user owns, for one staging pass -- see
     * {@link com.finora.imports.MerchantIndex}'s own doc comment for why this exists instead of
     * calling {@link #resolveReadOnly(UUID, String)} once per row. Exactly two queries, regardless
     * of the statement's row count: one for {@link MerchantRepository#findByUserId}, one for
     * {@link MerchantAliasRepository#findByUserId}.
     */
    public com.finora.imports.MerchantIndex indexFor(UUID userId) {
        Map<UUID, Merchant> merchantsById = new HashMap<>();
        List<Merchant> merchants = merchantRepository.findByUserId(userId);
        for (Merchant merchant : merchants) merchantsById.put(merchant.getId(), merchant);
        // The memo's own Grouping, not a second copy of its keying: same keys, same first-wins
        // tiebreak, so a staged preview cannot pick a different merchant than the confirm will.
        Grouping grouping = Grouping.of(merchants);
        Map<String, Merchant> byNormalizedAlias = new HashMap<>();
        for (MerchantAlias alias : merchantAliasRepository.findByUserId(userId)) {
            Merchant merchant = merchantsById.get(alias.getMerchantId());
            // A dangling alias (Bug 56) has no live merchant here -- correctly resolves to nothing,
            // the same as resolveReadOnly's findByIdAndUserId would.
            if (merchant != null) byNormalizedAlias.put(alias.getNormalizedAlias(), merchant);
        }
        return new com.finora.imports.MerchantIndex(byNormalizedAlias, grouping::match);
    }

    /**
     * Same reduction and precedence as {@link #resolveReadOnly(UUID, String)} -- exact alias first,
     * then the grouping key -- but against an {@link com.finora.imports.MerchantIndex} the caller
     * built once for the whole statement via {@link #indexFor}, so this issues no database queries
     * at all.
     */
    public java.util.Optional<Merchant> resolveReadOnly(UUID userId, String description,
                                                          com.finora.imports.MerchantIndex index) {
        String normalizedAlias = fitToColumn(CategoryRules.normalize(description));
        Merchant aliased = index.byNormalizedAlias(normalizedAlias);
        if (aliased != null) return java.util.Optional.of(aliased);

        return java.util.Optional.ofNullable(index.byGrouping(payeeText(description)));
    }

    private Merchant createMerchantAndAlias(UUID userId, String description, String normalizedAlias) {
        Merchant merchant = createMerchant(userId, description);
        addAlias(merchant.getId(), userId, normalizedAlias);
        return merchant;
    }

    /**
     * Bug 56's repair path: the alias row already exists (just pointing at a merchant that's gone),
     * so it must be repointed at this new merchant rather than inserted again -- see resolve()'s own
     * comment on why addAlias()/insertIfAbsent cannot do that repair itself.
     */
    private Merchant repointDanglingAlias(UUID userId, String description, MerchantAlias danglingAlias) {
        Merchant replacement = createMerchant(userId, description);
        danglingAlias.setMerchantId(replacement.getId());
        merchantAliasRepository.save(danglingAlias);
        return replacement;
    }

    private Merchant createMerchant(UUID userId, String description) {
        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        // merchants.canonical_name is VARCHAR(255) too, and is derived from the same description,
        // so it carries the identical hazard. Guarding only the alias would have moved the
        // rollback one insert further down rather than removing it.
        merchant.setCanonicalName(fitToColumn(toDisplayName(payeeText(description))));
        // TEMPORARY, because this is a GUESS. Everything reaching this line is a description the
        // engine has never seen, resolved by a grouping-key heuristic its own class doc
        // describes as "deliberately simple ... not fuzzy matching or NLP". Marking it says so, and
        // is what lets the Merchant Review Center show an operator the engine's guesses without
        // also showing them every merchant the user has genuinely transacted with.
        //
        // Never blocks the import. The merchant is created and returned exactly as before -- the
        // only difference is that it now admits what it is.
        merchant.setLifecycleStatus(Merchant.Lifecycle.TEMPORARY);
        return merchantRepository.save(merchant);
    }

    /** Never let parser output exceed what the column can hold; see resolve()'s own reasoning. */
    private static String fitToColumn(String value) {
        if (value == null || value.length() <= MAX_STORED_LENGTH) return value;
        return value.substring(0, MAX_STORED_LENGTH);
    }

    /**
     * <p><b>Bug fix, original.</b> This was check-then-act against
     * {@code UNIQUE(user_id, normalized_alias)}, with nothing between the check and the insert.
     * Two concurrent imports for the same user hitting the same new description both saw "no alias
     * yet" and both inserted — and {@code ImportConcurrencyLimiter}'s semaphore does not prevent
     * it, being a global permit count with no per-user scoping, so two imports for one user is an
     * ordinary occurrence rather than an exotic race (one person confirming two statements from two
     * tabs). The consequence was disproportionate: the losing insert threw
     * {@code DataIntegrityViolationException} from inside the confirm transaction, so the user's
     * ENTIRE import rolled back over a duplicate alias row — a row whose only purpose is caching a
     * name match, and which by definition already existed with the right value.
     *
     * <p><b>Bug fix, second (found investigating an unrelated reported race in CSV import staging,
     * which did not itself reproduce).</b> The first fix's own recovery path did not survive
     * contact with a genuine race. It was {@code saveAndFlush} inside a {@code try}, and on
     * {@code DataIntegrityViolationException}, re-querying to tell a real constraint problem
     * (rethrow) apart from a benign lost race (keep the existing row, log at DEBUG). That re-query
     * ran in the SAME transaction as the failed insert — {@code resolve()} is
     * {@code @Transactional} with the default REQUIRED propagation, and on the real call path
     * ({@code ImportService.confirm} looping {@code resolve()} once per staged row) that is the
     * whole import's transaction, not a private one. Checked by hand against this project's own
     * Postgres ({@code docker exec ... psql}): once any statement in an open transaction fails,
     * EVERY later statement on it — a plain {@code SELECT} included — fails with
     * {@code current transaction is aborted, commands ignored until end of transaction block}
     * (SQLSTATE {@code 25P02}) until {@code COMMIT} or {@code ROLLBACK}. There is no savepoint here
     * ({@code Propagation.NESTED} or an explicit {@code SAVEPOINT}) to undo just the failed insert,
     * so the "is this a real race" re-query threw too — confirmed empirically in
     * {@code MerchantConcurrentAliasRaceIT} as {@code JpaSystemException}, not the
     * {@code DataIntegrityViolationException} the surrounding catch was written to handle — and
     * propagated out of {@code resolve()} uncaught. A genuine two-writer race therefore failed the
     * WHOLE import over one alias that only ever needed to be treated as a duplicate, which is
     * worse than the bug the first fix closed. This had no test: {@code MerchantNormalizationEngineTest}
     * mocks the repository (a mock cannot reproduce Postgres's abort-the-whole-transaction
     * semantics), and the one real-Postgres test exercising this catch block,
     * {@code MerchantOversizedDescriptionIT}, only covered "not a race, rethrow" — never a
     * genuine race where the target row really is there, just unreachable from the poisoned
     * transaction.
     *
     * <p><b>Why this closes it rather than isolating it.</b> Wrapping just the re-query in its own
     * {@code Propagation.REQUIRES_NEW} transaction was considered and rejected: it would still
     * leave the AMBIENT transaction poisoned (a suspended-and-resumed transaction does not
     * un-abort), so even a successful re-query would only trade a loud exception for
     * {@code COMMIT} silently downgrading to {@code ROLLBACK} at the very end — Postgres's own
     * behaviour for a {@code COMMIT} issued against an aborted transaction — discarding the
     * import with no error at all. {@code MerchantLearningService.confirm}'s own doc comment
     * records the same verdict for the same shape of problem (BH-053): "the write simply must not
     * be attempted" once a transaction can be poisoned by it, matching what this class's own
     * {@link #resolve} already says about the oversized-narration case above. {@link
     * MerchantAliasRepository#insertIfAbsent} is an {@code INSERT ... ON CONFLICT DO NOTHING},
     * exactly {@link com.finora.repository.RegisteredLayoutRepository#observe}'s and {@link
     * MerchantCategoryLearningRepository#ensurePairExists}'s pattern: the database resolves the
     * conflict atomically and silently, so a benign lost race never raises an exception in the
     * first place and the ambient transaction is never poisoned. It stays in the CALLER's
     * transaction rather than {@code REQUIRES_NEW} for the same reason {@code ensurePairExists}
     * does — {@code merchant_id} is a {@code NOT NULL} foreign key, and on the
     * brand-new-merchant path ({@link #createMerchantAndAlias}) that parent row was routinely just
     * {@code save()}d in this SAME still-uncommitted transaction; a suspended-and-restarted inner
     * transaction could not see it and every first-time merchant would fail its foreign-key check.
     *
     * <p>A different, unexpected constraint failure (there should not be one — {@code merchantId}
     * always names a row this same transaction can see, and {@code normalizedAlias} is already
     * {@link #fitToColumn}-truncated before it reaches here) is no longer silently caught at all:
     * {@code ON CONFLICT (user_id, normalized_alias)} only suppresses THAT constraint, so anything
     * else still raises normally and propagates, which is the correct outcome for a genuinely
     * unexpected failure — see {@link #resolve}'s own reasoning for why catching it here cannot
     * help once it has happened.
     */
    private void addAlias(UUID merchantId, UUID userId, String normalizedAlias) {
        int inserted = merchantAliasRepository.insertIfAbsent(merchantId, userId, normalizedAlias);
        if (inserted == 0) {
            log.debug("Alias '{}' for user {} already existed (an earlier call in this same "
                    + "transaction, or a concurrent writer that got there first); keeping the "
                    + "existing row.", normalizedAlias, userId);
        }
    }

    /**
     * The token this description or canonical name is grouped by — the first word that names a
     * COUNTERPARTY rather than a payment rail or a reference number.
     *
     * <h2>Two things had to change together, and only together</h2>
     *
     * <p><b>1. Skip payment rails ({@link PaymentRailTokens}).</b> This used to accept any token
     * longer than two characters. Indian narrations are shaped
     * {@code <rail>/<reference>/<counterparty>}, and every rail name is longer than two characters,
     * so the grouping key for "UPI/9182736/SWIGGY" was {@code upi}. So was the key for
     * "UPI/5647382/ZOMATO", and for every other UPI row on the statement — they all matched
     * whichever UPI payee was seen first and aliased onto it. On a real Indian bank statement that
     * is close to every transaction collapsing into one merchant, which then teaches
     * {@code ConfidenceEngine.topCategory} a category drawn from hundreds of unrelated payees.
     *
     * <p><b>2. Tokenise the SAME text on both sides.</b> Skipping the rail alone would have made
     * this worse, not better. The incoming side used to tokenise the raw normalised alias, which
     * still contains the reference number — so "upi 9182736 swiggy" would have skipped {@code upi}
     * and grouped on {@code 9182736}, a value unique to that one transaction. Every row would have
     * become its own merchant: under-grouping as total as the over-grouping it replaced.
     *
     * <p>Callers therefore pass {@link #payeeText} rather than
     * {@code CategoryRules.normalize(description)}. Both of its sources already strip reference
     * tokens, and it is also what {@code createMerchantAndAlias} builds the canonical name from —
     * so the incoming description and the stored merchant are reduced by the identical rule before
     * they are compared, which is what makes the match symmetric by construction rather than by
     * two lists that have to be kept in step.
     *
     * <p>Returning null when nothing survives is deliberate: a description that is nothing but
     * rail and reference ("UPI 12345") has no counterparty to group by, and inventing one would be
     * the original bug in miniature. A null key means "create this merchant on its own" — a
     * duplicate the user can merge, which is the failure direction this class can afford.
     *
     * <h2>Then: two words, not one</h2>
     *
     * <p>The key used to be the first significant word alone. Measured on the real corpus (29
     * statements, 1,688 rows, each confirmed and then re-suggested once the learning queue had
     * drained), 94 merchants held more than one payee and 37 rows had their category replaced by
     * a pooled merchant's learned one -- 31 of them wrongly, because a learned category outranks
     * the keyword table and the person-transfer rule alike. The first word was often not a name at
     * all: a narration format whose payee field opens with a preposition pooled six people with a
     * phone company, and a card's glued direction codes pooled nine unrelated payees. Elsewhere it
     * was a common word that a clearing house, a railway ticket counter and a food stall all begin
     * with; or a first name shared by a person and a shop, which had not misfired yet only because
     * neither side had taught a category. Keying on the first two significant words left none of
     * the 31.
     *
     * <p>What it costs: a brand's narrations stop collapsing when they differ in the second word.
     * {@link #brandToken} gives that back for the merchants that exist to catch every spelling of a
     * brand, and nowhere else.
     */
    private static String groupingKey(String reduced) {
        if (reduced == null || reduced.isBlank()) return null;
        List<String> significant = significantTokens(reduced);
        if (!significant.isEmpty()) return String.join(" ", significant.subList(0, Math.min(2, significant.size())));
        // Preserves the pre-existing short-token fallback (a merchant genuinely named "HP" still
        // groups) while keeping rails excluded, so this only ever narrows what may become a key.
        for (String t : reduced.split(" ")) {
            if (!t.isBlank() && !PaymentRailTokens.isRailToken(t)) return t;
        }
        return null;
    }

    /**
     * The words of a reduced name that can name a counterparty: longer than two characters, not a
     * payment rail, and not digits alone. In order.
     *
     * <p>Digits alone, because {@code extractMerchant} strips only references of four digits or
     * more. A shorter one survives ("SWIGGY REF 447" reduces to "swiggy ref 447", and "ref" is a
     * rail word), and as the second word of a key it would give every row of one payee a merchant
     * of its own. Five rows of the real corpus carried one there. No first-word key on the corpus
     * was digits alone, and a name made only of digits still keys through {@link #groupingKey}'s
     * fallback, as it did before.
     */
    private static List<String> significantTokens(String reduced) {
        if (reduced == null || reduced.isBlank()) return List.of();
        List<String> significant = new java.util.ArrayList<>();
        for (String t : reduced.split(" ")) {
            if (t.length() > 2 && !PaymentRailTokens.isRailToken(t) && !t.chars().allMatch(Character::isDigit)) {
                significant.add(t);
            }
        }
        return significant;
    }

    /**
     * The text a description is grouped by and a new merchant is named after: the payee field of
     * a structured narration when it names someone, else {@code CategoryRules.extractMerchant}.
     *
     * <p>extractMerchant keeps only the first four words. A narration that prints its payee after
     * a long prefix -- "UPI/RRN &lt;n&gt;/Payment from PhonePe_&lt;NAME&gt;" -- loses the name
     * entirely, and every payee of that format reduced to the same four words. On the real corpus
     * six different people were one merchant this way; they had not misfired yet only because
     * nothing had taught that merchant a category, and one correction on any of them would have
     * applied it to all six. {@code CategoryRules.structuredPayee} reads the payee field itself --
     * the same field the transaction's own merchant label already shows.
     *
     * <p>A structured narration whose payee field names nobody falls back to extractMerchant, so
     * it groups exactly as before.
     */
    private static String payeeText(String description) {
        String payee = CategoryRules.structuredPayee(description);
        return payee != null ? payee : CategoryRules.extractMerchant(description);
    }

    private String toDisplayName(String extractedMerchant) {
        if (extractedMerchant == null || extractedMerchant.isBlank()) return "Unknown Merchant";
        String[] words = extractedMerchant.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
