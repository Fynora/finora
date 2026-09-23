package com.finora.dto;

import java.util.List;

/**
 * A batch of navigation events.
 *
 * <p>Deliberately minimal. There is no user, session or device field, and adding one would reopen
 * both the consent question and the published privacy-policy wording -- the absence of identity is
 * the whole basis on which this is collected without a prompt. {@code NavEventControllerTest}
 * asserts this record's component names for exactly that reason.
 *
 * <p>The platform is not carried here either: it is resolved server-side from
 * {@code ClientIdentity}, so a caller cannot assert it in the body.
 *
 * <p>{@code searches} is a plain count rather than a list, because a search event has nothing to
 * carry. Recording <em>what</em> was searched for is forbidden --
 * {@code docs/engineering/observability.md} §3 names the ledger search term specifically -- so a
 * count is the entire payload.
 */
public record NavEventRequest(List<Event> events, Integer searches) {
    public record Event(String destination, String group, String entry) {}
}
