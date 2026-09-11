package com.finora.dto;

import java.time.Instant;

public record TimelineEventDto(String eventType, String bucket, String importance,
                                boolean permanent, String title, String detail, Instant occurredAt) {}
