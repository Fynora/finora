package com.finora.service;

/**
 * How sensitive a Fyn tool's return value is allowed to be -- see docs/superpowers/specs/
 * 2026-09-13-fino-ai-implementation-plan.md §4.1. Every {@link FynToolDescriptor} declares one;
 * the large majority of Fyn's planned tools sit at {@link #TIER_1_AGGREGATE} by design (plan §3),
 * and nothing planned for Phases 1-4 uses {@link #TIER_3_DESCRIPTION} or {@link #TIER_4_IDENTIFIER}
 * at all -- they exist so the ceiling is nameable, not because a tool is expected to reach them.
 */
public enum FynDataTier {
    /** Public metadata -- category lists, currency. */
    TIER_0_PUBLIC,
    /** Aggregates -- category totals, budget status, balance summaries. Where almost every Fyn
     *  tool lives. */
    TIER_1_AGGREGATE,
    /** Merchant names. Nothing planned for Phases 1-4 uses this. */
    TIER_2_MERCHANT,
    /** Transaction descriptions/narrations. Never exposed to Claude per plan §3. */
    TIER_3_DESCRIPTION,
    /** Account numbers, UPI IDs, reference numbers. Never exposed to Claude per plan §3. */
    TIER_4_IDENTIFIER
}
