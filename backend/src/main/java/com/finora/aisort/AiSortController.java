package com.finora.aisort;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.observability.AiSortMetrics;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The AI sorting card on the import Done step (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md).
 * The customer is always the signed-in caller, never a parameter.
 */
@RestController
@RequestMapping("/api/v1/ai-sort")
public class AiSortController {

    private final AiSortService service;
    private final AiSortRunner runner;
    private final CurrentUser currentUser;
    private final AiSortMetrics metrics;

    public AiSortController(AiSortService service, AiSortRunner runner, CurrentUser currentUser, AiSortMetrics metrics) {
        this.service = service;
        this.runner = runner;
        this.currentUser = currentUser;
        this.metrics = metrics;
    }

    /** Whether to show the card for the statement import(s) a confirm just made, and with what numbers. */
    @GetMapping("/offer")
    public ApiResponse<AiSortDto.Offer> offer(
            @RequestParam(name = "statementImportIds", required = false) List<UUID> statementImportIds) {
        if (statementImportIds != null && statementImportIds.size() > AiSortService.MAX_IDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many statement imports.");
        }
        AiSortDto.Offer offer = service.offer(currentUser.id(), statementImportIds == null ? List.of() : statementImportIds);
        if (offer.show()) metrics.offerShown(offer.mode());
        return ApiResponse.ok(offer);
    }

    /** A tap: sort, turn AI on and sort, or no thanks. The run's work starts after it is saved. */
    @PostMapping("/runs")
    public ApiResponse<AiSortDto.Run> start(@Valid @RequestBody AiSortDto.StartRequest request) {
        UUID userId = currentUser.id();
        AiSortService.Started started;
        try {
            started = service.start(userId, request);
        } catch (DataIntegrityViolationException e) {
            // Two taps at once: the other one saved the run first.
            return ApiResponse.ok(service.existingRun(userId, request.statementImportIds()).map(AiSortDto.Run::of)
                    .orElseThrow(() -> e));
        }
        if (started.isNew()) {
            AiSortRun run = started.run().orElseThrow();
            runner.launch(run.getId(), userId, request.statementImportIds().stream().distinct().sorted().toList());
        }
        return ApiResponse.ok(started.run().map(AiSortDto.Run::of).orElse(null));
    }

    /** A run's progress; the card polls it while RUNNING. */
    @GetMapping("/runs/{id}")
    public ApiResponse<AiSortDto.Run> run(@PathVariable UUID id) {
        return ApiResponse.ok(service.run(currentUser.id(), id));
    }
}
