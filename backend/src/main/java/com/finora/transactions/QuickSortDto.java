package com.finora.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Quick sort: a few payee questions in place of every waiting row. See QuickSortService. */
public final class QuickSortDto {

    private QuickSortDto() {}

    /** What a question asks, which decides its copy and its first answers. */
    public enum Kind {
        /** Money paid to a person: nothing in the narration says what for. */
        PERSON_PAID,
        /** Money paid to a shop or business nothing recognised. */
        SHOP,
        /** A rule, the shared corpus or the AI already guessed a category: confirm or change it. */
        GUESS,
        /** Money received. */
        MONEY_IN
    }

    /**
     * One batch of questions.
     *
     * @param waitingTotal all money still waiting for review, including this batch -- the client
     *                     measures progress against the first batch's figure
     * @param rest         what is left after this batch, for "Stop asking about these"
     */
    public record Batch(List<Question> questions, BigDecimal waitingTotal, Rest rest) {}

    /**
     * One payee (or one row, when its key names no single payee).
     *
     * @param id                  stable for the payee: its key and direction, or "row:" and the row's id
     * @param anchorTransactionId the payee's latest waiting row; answering files from it
     * @param payee               the readable name (PayeeLabel), never the key
     * @param largeOneOff         a single payment of at least Rs 5,000
     * @param currentCategory     the anchor's category now ("Other", "Personal Transfer" or a guess)
     * @param answers             up to five likely categories, best first; the client adds "More..."
     */
    public record Question(String id, UUID anchorTransactionId, Kind kind, String payee, int payments,
                           BigDecimal total, LocalDate latestDate, boolean largeOneOff, String currentCategory,
                           List<String> answers, List<Sample> samples) {}

    public record Sample(UUID id, LocalDate date, String description, BigDecimal amount, String type) {}

    /** @param transactionIds every waiting row in the groups after this batch */
    public record Rest(int questions, int payments, BigDecimal amount, List<UUID> transactionIds) {}

    /** An answer to one question: file the payee (or the one row) from its anchor. */
    public record AnswerRequest(@jakarta.validation.constraints.NotNull UUID anchorTransactionId,
                                @jakarta.validation.constraints.NotBlank String category,
                                @jakarta.validation.constraints.NotNull Kind kind) {}

    /** @param filed how many of the payee's rows the answer filed: the anchor, and for a key that names
     *               one payee every other row of it the user had not chosen by hand (waiting or not,
     *               as "apply to all similar" does) */
    public record AnswerResult(int filed) {}

    /** "Stop asking about these": the rest's ids from the batch. Capped so one request stays bounded. */
    public record KeepRestRequest(@jakarta.validation.constraints.NotNull
                                  @jakarta.validation.constraints.Size(max = 2000) List<UUID> transactionIds) {}

    /** @param cleared how many rows left the review queue */
    public record KeepRestResult(int cleared) {}
}
