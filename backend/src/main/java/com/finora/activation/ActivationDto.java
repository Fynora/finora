package com.finora.activation;

import jakarta.validation.constraints.NotNull;

public final class ActivationDto {

    private ActivationDto() {}

    /** An unknown event name fails deserialization and is refused with 400. */
    public record FunnelEventRequest(@NotNull FunnelEvent event) {}
}
