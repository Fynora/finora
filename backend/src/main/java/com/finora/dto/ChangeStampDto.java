package com.finora.dto;

/**
 * One opaque value per kind of data the mobile app shows, each of which changes exactly when that
 * kind of data changes for the caller. A client compares each with the last one it saw and never
 * reads anything out of them. See {@link com.finora.service.ChangeStampService}.
 */
public record ChangeStampDto(
        String transactions,
        String accounts,
        String statementImports,
        String budgets,
        String goals,
        String categories,
        String profile) {}
