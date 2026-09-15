package com.finora.imports;

import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.SharedMerchantCategoryAiSuggestion;
import com.finora.entity.Transaction;
import com.finora.repository.SharedMerchantCategoryAiSuggestionRepository;
import com.finora.service.CategorizationService;
import com.finora.service.SharedCorpusService;
import com.finora.util.CounterpartyTyping;
import com.finora.util.EnumParsing;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decides, for each confirmed row, whether the category assignment was a real decision worth
 * teaching the merchant-learning map, or an unresolved guess the user just left alone. Named
 * distinctly from the top-level RuleEngineService (global/user category_rules matching) — this
 * class is specifically about the import pipeline's confirm-time learning step.
 *
 * <p><b>WI1 changed what this class does with that decision.</b> It used to apply the learning
 * itself, calling {@code CategorizationService.learn} inline, which put a synchronous merchant
 * confirmation inside the import's transaction once per confirmed row — the direct cause of Bug 02.
 * It now only DECIDES, and returns that decision; {@code ImportService} queues the learning after
 * the statement import row exists, and a worker applies it after the transaction commits. Deciding
 * and applying were never the same job; they only looked like it while both were synchronous.
 */
@Component
public class ImportRuleLearningService {

    private final CategorizationService categorizationService;
    private final SharedCorpusService sharedCorpusService;
    private final SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository;

    public ImportRuleLearningService(CategorizationService categorizationService,
                                      SharedCorpusService sharedCorpusService,
                                      SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository) {
        this.categorizationService = categorizationService;
        this.sharedCorpusService = sharedCorpusService;
        this.aiSuggestionRepository = aiSuggestionRepository;
    }

    /**
     * What this row taught, if anything.
     *
     * @param unresolvedGuess the row's category was never actually resolved — the caller sets the
     *                        transaction's {@code needsCategoryReview} flag from this
     * @param worthLearning   whether a merchant-learning confirmation should be queued for this row
     */
    public record Decision(boolean unresolvedGuess, boolean worthLearning) {}

    /**
     * Records the rule match for a confirmed row and reports whether its category assignment is
     * worth learning from.
     *
     * <p>An unresolved guess (the engine's original guess was "default" — no rule or learned match,
     * so it fell through to Other — and review left it as Other) teaches nothing. Learning from one
     * would poison the merchant map with "this merchant = Other" for no reason.
     *
     * <p>A structurally-detected person-to-person payment that review left as "Personal Transfer" is
     * the same kind of non-decision, for a sharper reason: that detector discloses an 8-12% error
     * bound, and a learned confirmation outranks the keyword table permanently — so teaching from
     * an unconfirmed misfire would file a real business as a personal payment forever, invisibly.
     * See {@link CategorizationService#isUnconfirmedGuess}, which is the single definition both
     * this and the review-queue flag are derived from.
     *
     * <p><b>One transitional case is knowingly not covered.</b> A session staged before "Paid a
     * Person" existed carries the pair {@code (structural_p2p, "Transfer")}, which this now reads
     * as "review changed it" rather than as an unconfirmed guess -- so if it is confirmed after
     * the deploy, it is learned. That was preferred to special-casing the old pair, because the
     * pair is genuinely ambiguous and the two readings are not equally common: post-deploy, moving
     * one of these rows to "Transfer" is one of the likeliest corrections a user makes on it, and
     * permanently refusing to learn from that real decision costs more than the alternative. The
     * exposure is bounded to one 48h {@code ImportSessionService.SESSION_TTL} window per deploy,
     * to sessions staged inside it, and within those, to rows where the detector actually
     * misfired.
     *
     * <p>No longer applies the learning itself: see this class's doc comment.
     *
     * <p><b>shared_corpus/ai_fallback is its own case, not delegated to
     * {@code CategorizationService.isUnconfirmedGuess} for this decision.</b> That method
     * unconditionally reports these two sources as unconfirmed regardless of category -- correct
     * at staging time (a freshly-generated statistical guess always needs review, see its own doc
     * comment), wrong here: it would mean a row the user actively corrected during import review
     * never teaches anything, silently. Unlike {@code "default"}/{@code structural_p2p}, which
     * each have one fixed non-decision output to compare {@code category} against ({@code "Other"}
     * / {@link CategorizationService#P2P_CATEGORY}), a shared-corpus or AI suggestion is an
     * arbitrary category with no fixed value here to compare it to -- {@code ConfirmedRow} never
     * carried the originally-suggested category through from staging. {@link #matchesLiveSuggestion}
     * re-queries the corpus/AI cache's CURRENT answer as a stand-in: if the row's final category
     * still matches that live answer, treat it the same as an untouched default (not worth
     * learning -- avoids feeding an accepted-without-scrutiny batch confirm back in as if it were
     * corroborating evidence); if it differs, the user corrected it, which is exactly the signal
     * worth learning from. This is an approximation of "did review change it" (the live answer can
     * differ from what was shown at staging time if it changed in between), not the exact staged
     * value -- acceptable given the staging-to-confirm window for one import is normally seconds to
     * minutes, not the alternative of never learning from these two sources at all.
     */
    public Decision recordDecision(ConfirmedRow row) {
        boolean isUnresolvedGuess = isUnconfirmedGuess(row);

        // This IS the actual write, unlike the suggest() call at staging time (preview, possibly
        // never confirmed) -- row.ruleId() is the same ASSIGN_CATEGORY rule id resolved there and
        // carried through review unchanged, so it's recorded here rather than by re-evaluating
        // rules against the confirmed row.
        categorizationService.recordRuleMatch(row.ruleId());

        return new Decision(isUnresolvedGuess, !isUnresolvedGuess);
    }

    private boolean isUnconfirmedGuess(ConfirmedRow row) {
        String source = row.categorySource();
        boolean isCorpusOrAiSourced = CategorizationService.SHARED_CORPUS_SOURCE.equals(source)
                || CategorizationService.AI_FALLBACK_SOURCE.equals(source);
        if (isCorpusOrAiSourced) {
            return matchesLiveSuggestion(row);
        }
        return CategorizationService.isUnconfirmedGuess(source, row.category());
    }

    /** @return true if the row's final category still equals the corpus/AI cache's current
     *  answer for its counterparty+direction (ambiguous -- could just be the untouched
     *  suggestion), or if there is no live answer to compare against at all (nothing to prove a
     *  correction happened, so this is NOT treated as unconfirmed -- the same "no signal either
     *  way, don't withhold learning" default {@code CategorizationService.isUnconfirmedGuess}
     *  applies to any other unrecognized source). */
    private boolean matchesLiveSuggestion(ConfirmedRow row) {
        CounterpartyTyping typing = CounterpartyTyping.of(row.description());
        Transaction.Type direction = EnumParsing.parse(Transaction.Type.class, row.type(), "type");
        Optional<String> liveAnswer = CategorizationService.SHARED_CORPUS_SOURCE.equals(row.categorySource())
                ? sharedCorpusService.findTrustedSuggestion(typing.key(), typing.type(), direction)
                : aiSuggestionRepository.findByCounterpartyKeyAndDirection(typing.key(), direction)
                        .map(SharedMerchantCategoryAiSuggestion::getCategory);
        return liveAnswer.isPresent() && liveAnswer.get().equals(row.category());
    }
}
