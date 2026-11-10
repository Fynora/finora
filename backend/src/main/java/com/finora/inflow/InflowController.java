package com.finora.inflow;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.security.CurrentUser;
import com.finora.service.InflowChoices;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Inflow kinds and the user's choices (Plan 2, docs/superpowers/specs/2026-09-27-inflow-kinds-design.md). */
@RestController
@RequestMapping("/api/v1")
public class InflowController {

    private final InflowKindService kinds;
    private final InflowReviewService review;
    private final CurrentUser currentUser;

    public InflowController(InflowKindService kinds, InflowReviewService review, CurrentUser currentUser) {
        this.kinds = kinds;
        this.review = review;
        this.currentUser = currentUser;
    }

    @GetMapping("/inflow-kinds")
    public ApiResponse<List<InflowDtos.InflowKindDto>> listInflowKinds() {
        return ApiResponse.ok(kinds.list(currentUser.id()));
    }

    @PostMapping("/inflow-kinds")
    public ApiResponse<InflowDtos.InflowKindDto> createInflowKind(@Valid @RequestBody InflowDtos.CreateKindRequest req) {
        return ApiResponse.ok(kinds.create(currentUser.id(), req));
    }

    @PatchMapping("/inflow-kinds/{id}")
    public ApiResponse<InflowDtos.InflowKindDto> updateInflowKind(@PathVariable UUID id,
                                                        @Valid @RequestBody InflowDtos.UpdateKindRequest req) {
        return ApiResponse.ok(kinds.update(currentUser.id(), id, req));
    }

    @DeleteMapping("/inflow-kinds/{id}")
    public ApiResponse<Void> deleteInflowKind(@PathVariable UUID id) {
        kinds.delete(currentUser.id(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/transactions/{id}/counts-as")
    public ApiResponse<InflowDtos.CountsAsDto> getCountsAs(@PathVariable UUID id) {
        return ApiResponse.ok(kinds.countsAs(currentUser.id(), id));
    }

    @PutMapping("/transactions/{id}/inflow-kind")
    public ApiResponse<InflowDtos.CountsAsDto> setInflowKindChoice(@PathVariable UUID id,
                                                         @Valid @RequestBody InflowDtos.SetChoiceRequest req) {
        return ApiResponse.ok(kinds.setChoice(currentUser.id(), id, req));
    }

    @DeleteMapping("/transactions/{id}/inflow-kind")
    public ApiResponse<InflowDtos.CountsAsDto> clearInflowKindChoice(@PathVariable UUID id, @RequestParam InflowChoices.Scope scope) {
        return ApiResponse.ok(kinds.clearChoice(currentUser.id(), id, scope));
    }

    @GetMapping("/sender-inflow-rules")
    public ApiResponse<List<InflowDtos.SenderRuleDto>> listSenderInflowRules() {
        return ApiResponse.ok(kinds.senderRules(currentUser.id()));
    }

    @DeleteMapping("/sender-inflow-rules/{id}")
    public ApiResponse<Void> forgetSenderInflowRule(@PathVariable UUID id) {
        kinds.forgetSender(currentUser.id(), id);
        return ApiResponse.ok(null);
    }

    /** Either one statement import's money not counted as income (the import Done step), or a date
     *  range's (Money Review). Exactly one of the two must be given. */
    @GetMapping("/transactions/unresolved-inflows")
    public ApiResponse<List<InflowDtos.UnresolvedSenderDto>> listUnresolvedInflows(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) UUID statementImportId) {
        if (statementImportId != null && startDate == null && endDate == null) {
            return ApiResponse.ok(review.groupsForImport(currentUser.id(), statementImportId));
        }
        if (statementImportId == null && startDate != null && endDate != null) {
            return ApiResponse.ok(review.groups(currentUser.id(), startDate, endDate));
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Give either statementImportId, or both startDate and endDate");
    }
}
