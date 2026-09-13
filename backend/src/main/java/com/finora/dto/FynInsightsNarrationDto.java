package com.finora.dto;

/** A short, AI-composed summary of the user's own already-computed spending insights (Fyn Phase 3,
 *  docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md §6 Phase 3). Deliberately just
 *  a string, not a richer structure: {@link InsightsDto} already carries the structured numbers
 *  (movers, biggestCategory) a client can render precisely -- this is only the prose gloss on top. */
public record FynInsightsNarrationDto(String narration) {}
