package com.finora.controller;

import com.finora.dto.NavEventRequest;
import com.finora.entity.ClientPlatform;
import com.finora.observability.NavDestination;
import com.finora.observability.NavEntryPoint;
import com.finora.observability.NavGroup;
import com.finora.observability.NavigationMetrics;
import com.finora.support.ClientIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Records aggregate navigation usage. Validates, increments, returns 204.
 *
 * <h2>Nothing is stored</h2>
 *
 * <p>No service layer and no repository, because there is no row to write. That is a design
 * constraint rather than an omission: with no events table there is no dataset to later
 * re-identify, leak, or be compelled to produce. {@code NavEventControllerTest} asserts this
 * structurally, because the natural future regression is someone adding a repository "just to keep
 * the raw events for debugging."
 *
 * <h2>Allowlist, not sanitisation</h2>
 *
 * <p>Every field is matched against a bounded enum and dropped when it does not match -- the rule
 * {@code SentryScrubber} already establishes for this codebase: structures are validated against
 * known values rather than cleaned by removing bad ones. Notably there is no "other" bucket. An
 * unrecognised value is the one thing a stale client or an attacker controls, and a bucket for it
 * is where the enum stops being bounded ({@code docs/engineering/observability.md} §5).
 *
 * <h2>Why the response never varies</h2>
 *
 * <p>Always 204, even for a body that is entirely rubbish. A client must learn nothing from this
 * endpoint, and navigation must never be blocked or slowed by measurement of it.
 *
 * <h2>Deliberately not rate-limited</h2>
 *
 * <p>{@code RateLimitFilter} limits "the handful of endpoints with a real, specific abuse cost" --
 * those reachable with no credential at all, plus CSV import staging, which persists a row holding
 * raw file bytes. Its doc is explicit that everything else is intentionally left unlimited, because
 * blanket rate limiting is "a different, heavier decision (needs per-endpoint tuning)."
 *
 * <p>This endpoint does not meet that bar. It requires a valid JWT, writes nothing, and does a
 * handful of in-memory counter increments -- a cheaper call than most authenticated reads that are
 * themselves unlimited. {@link #MAX_BATCH} already bounds the work any single request can cause.
 * The residual risk is a caller inflating aggregate counters, which is a data-quality concern
 * rather than a resource one, and not what that filter exists to solve.
 */
@RestController
@RequestMapping("/api/v1/nav-events")
public class NavEventController {

    /** One request cannot move more than this many counters, however long its body is. */
    public static final int MAX_BATCH = 50;

    private final NavigationMetrics metrics;
    private final ClientIdentity clientIdentity;

    public NavEventController(NavigationMetrics metrics, ClientIdentity clientIdentity) {
        this.metrics = metrics;
        this.clientIdentity = clientIdentity;
    }

    @PostMapping
    public ResponseEntity<Void> record(@RequestBody(required = false) NavEventRequest request) {
        // Resolved server-side, never read from the body. ClientIdentity reads the advisory
        // X-Client-Platform header and resolves an absent or unrecognised value to WEB by its own
        // documented decision -- that default is inherited rather than forked here.
        ClientPlatform platform = clientIdentity.platform();

        List<NavEventRequest.Event> events =
                request == null || request.events() == null ? List.of() : request.events();

        events.stream().limit(MAX_BATCH).forEach(event -> {
            Optional<NavDestination> destination = NavDestination.fromWire(event.destination());
            Optional<NavGroup> group = NavGroup.fromWire(event.group());
            Optional<NavEntryPoint> entry = NavEntryPoint.fromWire(event.entry());

            // All three must resolve. A half-valid event is dropped whole rather than recorded
            // against a partial tag set, which would quietly skew the distribution it feeds.
            if (destination.isPresent() && group.isPresent() && entry.isPresent()) {
                metrics.destinationOpened(destination.get(), group.get(), platform);
                metrics.entryPointUsed(entry.get(), platform);
            }
        });

        int searches = request == null || request.searches() == null
                ? 0 : Math.min(request.searches(), MAX_BATCH);
        for (int i = 0; i < searches; i++) {
            metrics.searchUsed(platform);
        }

        return ResponseEntity.noContent().build();
    }
}
