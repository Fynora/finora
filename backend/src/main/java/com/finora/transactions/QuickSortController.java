package com.finora.transactions;

import com.finora.dto.ApiResponse;
import com.finora.observability.QuickSortMetrics;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Quick sort: a few payee questions in place of every waiting row. See QuickSortService. */
@RestController
@RequestMapping("/api/v1/transactions/quick-sort")
public class QuickSortController {

    private final QuickSortService quickSortService;
    private final QuickSortMetrics metrics;
    private final CurrentUser currentUser;

    public QuickSortController(QuickSortService quickSortService, QuickSortMetrics metrics, CurrentUser currentUser) {
        this.quickSortService = quickSortService;
        this.metrics = metrics;
        this.currentUser = currentUser;
    }

    /**
     * One batch, after the first {@code skip} payees (the ones the user skipped). {@code preview}:
     * the caller only shows how many questions there are (the import summary's button), so it is
     * not counted as a batch shown.
     */
    @GetMapping
    public ApiResponse<QuickSortDto.Batch> batch(@RequestParam(defaultValue = "0") int skip,
                                                 @RequestParam(defaultValue = "false") boolean preview) {
        QuickSortDto.Batch batch = quickSortService.batch(currentUser.id(), Math.max(skip, 0));
        if (!preview) metrics.batchShown(batch.questions().size());
        return ApiResponse.ok(batch);
    }

    @PostMapping("/answer")
    public ApiResponse<QuickSortDto.AnswerResult> answer(@Valid @RequestBody QuickSortDto.AnswerRequest request) {
        return ApiResponse.ok(quickSortService.answer(currentUser.id(), request));
    }

    /** "Sort 10 more": recorded so the usage counters show whether one batch is enough. */
    @PostMapping("/more")
    public ApiResponse<Void> more() {
        metrics.moreTaken();
        return ApiResponse.ok(null);
    }

    @PostMapping("/keep-rest")
    public ApiResponse<QuickSortDto.KeepRestResult> keepRest(@Valid @RequestBody QuickSortDto.KeepRestRequest request) {
        return ApiResponse.ok(quickSortService.keepRest(currentUser.id(), request));
    }
}
