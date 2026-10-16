package com.finora.activation;

import jakarta.validation.constraints.NotNull;

public final class ActivationDto {

    private ActivationDto() {}

    /** An unknown event name fails deserialization and is refused with 400. */
    public record FunnelEventRequest(@NotNull FunnelEvent event) {}

    /** The dashboard question to show; {@code moment} is null when there is none. */
    public record QuestionResponse(ActivationMoment moment) {}

    /** An unknown moment or answer name fails deserialization and is refused with 400. Not named
     *  AnswerRequest: springdoc names schemas by simple class name, and QuickSortDto.AnswerRequest
     *  already has that name, so the two would share one schema in the API document. */
    public record ActivationAnswerRequest(@NotNull ActivationMoment moment, @NotNull ActivationAnswer answer) {}
}
