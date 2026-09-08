package com.finora.dto;

/** GET /api/v1/usage/{feature}/view-count response DTOs. */
public class FeatureUsageDtos {

    public record ViewCountResponse(int viewCount) {}
}
