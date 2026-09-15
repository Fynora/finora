package com.finora.dto;

import java.math.BigDecimal;

/** Spec §10's core metrics -- row counts per tier and the promotion rate. */
public record SharedCorpusMetricsDto(long trustedRows, long disputedRows, long revalidatingRows,
                                      BigDecimal promotionRate) {}
