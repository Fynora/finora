package com.finora.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * How Quick sort is used, so its 80% / 10-question cut can be judged on evidence rather than on the
 * one account it was measured on. Counts only: no payee, no amount, no user.
 */
@Component
public class QuickSortMetrics {

    private final MeterRegistry registry;

    public QuickSortMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A batch was fetched, with this many questions in it. */
    public void batchShown(int questions) {
        counter("finora.quick_sort.batches_shown", "A Quick sort batch was shown").increment();
        counter("finora.quick_sort.questions_shown", "Questions in the Quick sort batches shown").increment(questions);
    }

    /** A question was answered; {@code kind} is the question's kind (PERSON_PAID, SHOP, GUESS, MONEY_IN). */
    public void answered(String kind) {
        Counter.builder("finora.quick_sort.answered")
                .description("A Quick sort question was answered")
                .tag("kind", kind)
                .register(registry)
                .increment();
    }

    /** "Sort 10 more" was taken: one batch was not enough. */
    public void moreTaken() {
        counter("finora.quick_sort.more_taken", "Quick sort's \"Sort 10 more\" was taken").increment();
    }

    /** "Stop asking about these" was taken, clearing this many rows. */
    public void stopAskingTaken(int rows) {
        counter("finora.quick_sort.stop_asking_taken", "Quick sort's \"Stop asking about these\" was taken").increment();
        counter("finora.quick_sort.stop_asking_rows", "Rows Quick sort stopped asking about").increment(rows);
    }

    /** The category of a row answered in Quick sort was changed again later. */
    public void answerChangedLater() {
        counter("finora.quick_sort.answer_changed_later", "A Quick sort answer was changed later").increment();
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }
}
