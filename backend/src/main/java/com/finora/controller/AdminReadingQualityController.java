package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReadingQualityDto;
import com.finora.service.ReadingQualityService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The "Reading quality by bank" page ({@link ReadingQualityService}). {@code PLATFORM_DIAGNOSTICS_VIEW},
 * like Import Health and Trust Review Metrics beside it: engineering counts about how the reader
 * does per bank, with no one person's statement, file or id in the response.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/reading-quality")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminReadingQualityController {

    private final ReadingQualityService readingQuality;
    private final com.finora.service.StagedOutcomesService stagedOutcomes;

    public AdminReadingQualityController(ReadingQualityService readingQuality,
                                         com.finora.service.StagedOutcomesService stagedOutcomes) {
        this.readingQuality = readingQuality;
        this.stagedOutcomes = stagedOutcomes;
    }

    /** @param weeks 4 or 12: this week and the ones before it */
    @GetMapping
    public ApiResponse<ReadingQualityDto.Report> readingQuality(@RequestParam(defaultValue = "12") int weeks) {
        return ApiResponse.ok(readingQuality.report(weeks, Instant.now()));
    }

    /** What became of customers' staged statements, week by week. Counts only. @param weeks 4 or 12 */
    @GetMapping("/staged-outcomes")
    public ApiResponse<com.finora.dto.StagedOutcomesDto.Report> stagedOutcomes(
            @RequestParam(defaultValue = "12") int weeks) {
        return ApiResponse.ok(stagedOutcomes.report(weeks, Instant.now()));
    }
}
