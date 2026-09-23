package com.finora.observability;

import com.finora.entity.ClientPlatform;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Aggregate navigation usage.
 *
 * <p>Same argument as {@link AuthMetrics}, applied to navigation instead of session policy:
 * without these counters, "did the navigation redesign change anything" is a guess rather than a
 * measurement. They exist so the shared navigation taxonomy has a before-period to be compared
 * against, and they must be collecting before that change ships -- there is no backfill.
 *
 * <h2>Why these carry a platform tag when AuthMetrics refuses one</h2>
 *
 * <p>{@link AuthMetrics} declines to tag by client platform because {@code X-Client-Platform} is
 * client-asserted and its counters are security-relevant. Neither condition holds here. These are
 * product telemetry rather than a security control, and whether the two clients behave differently
 * is the entire question being asked -- unanswerable without the tag. A client that lies about its
 * platform skews a usage chart and nothing else. The divergence from that precedent is deliberate,
 * not an oversight.
 *
 * <p>The tag takes {@link ClientPlatform} rather than a free string, so the value is bounded by the
 * type system and splits Android from iOS for free. Note that {@code ClientIdentity} resolves an
 * absent or unrecognised header to {@link ClientPlatform#WEB} by its own documented decision --
 * rejecting a request over a cosmetic header "would turn a typo in a client into an outage." That
 * default is inherited here rather than forked: unheadered traffic reads as web, which is what it
 * overwhelmingly is, since the mobile clients send the header as an established contract.
 *
 * <h2>What is never here</h2>
 *
 * <p>No user, account, session or device identifier, and no free text. The absence of identity is
 * the basis on which this is collected without a consent prompt at all, so it is a constraint to
 * preserve rather than an accident of the current shape: adding an identifier reopens both the
 * consent question and the published privacy-policy wording.
 */
@Component
public class NavigationMetrics {

    private final MeterRegistry registry;

    public NavigationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A destination was opened, however it was reached. */
    public void destinationOpened(NavDestination destination, NavGroup group, ClientPlatform platform) {
        Counter.builder("finora.nav.destination_opened")
                .description("A navigation destination was opened")
                .tag("destination", destination.wire())
                .tag("group", group.wire())
                .tag("platform", platform.name().toLowerCase())
                .register(registry)
                .increment();
    }

    /** Which affordance carried the user there -- a group entry, a tab, the FAB, a header action,
     *  a contextual link, or search. */
    public void entryPointUsed(NavEntryPoint entry, ClientPlatform platform) {
        Counter.builder("finora.nav.entry_point_used")
                .description("The affordance a navigation destination was reached through")
                .tag("entry", entry.wire())
                .tag("platform", platform.name().toLowerCase())
                .register(registry)
                .increment();
    }

    /** A navigation search was performed. Counts that it happened; never what was typed --
     *  {@code docs/engineering/observability.md} §3 names the ledger search term as the sharpest
     *  case of free text that must never leave the platform. */
    public void searchUsed(ClientPlatform platform) {
        Counter.builder("finora.nav.search_used")
                .description("A navigation search was performed")
                .tag("platform", platform.name().toLowerCase())
                .register(registry)
                .increment();
    }
}
