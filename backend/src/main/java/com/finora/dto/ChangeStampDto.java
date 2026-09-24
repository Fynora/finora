package com.finora.dto;

/**
 * One opaque value per kind of data the mobile app shows, each of which changes exactly when that
 * kind of data changes for the caller. {@code preferences} (timezone, low-balance threshold) is
 * separate from {@code profile} because it changes what the server CALCULATES for other screens;
 * {@code billing} is the subscription and any referral reward, i.e. what unlocks features. A client compares each with the last one it saw and never
 * reads anything out of them. See {@link com.finora.service.ChangeStampService}.
 */
public record ChangeStampDto(
        String transactions,
        String accounts,
        String statementImports,
        String budgets,
        String goals,
        String categories,
        String profile,
        String preferences,
        String billing) {}
