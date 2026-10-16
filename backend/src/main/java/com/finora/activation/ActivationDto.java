package com.finora.activation;

import jakarta.validation.constraints.NotNull;

public final class ActivationDto {

    private ActivationDto() {}

    /** An unknown event name fails deserialization and is refused with 400. */
    public record FunnelEventRequest(@NotNull FunnelEvent event) {}

    /** The dashboard question to show; {@code moment} is null when there is none. */
    public record QuestionResponse(ActivationMoment moment) {}

    /** An unknown moment or answer name fails deserialization and is refused with 400. */
    public record AnswerRequest(@NotNull ActivationMoment moment, @NotNull ActivationAnswer answer) {}
}
