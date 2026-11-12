package com.finora.observability;

import com.finora.entity.ClientPlatform;
import com.finora.invite.InvitePromptEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Aggregate counts for the invite-a-friend card: how often it was shown, Share tapped, and Not now
 * tapped, by platform. Same rules as {@link NavigationMetrics}: a bounded enum for every tag, the
 * platform from {@code X-Client-Platform} (product telemetry, so a client misreporting it only skews a
 * chart), and no user, account, session or device identifier, ever.
 *
 * <p>These count events (two Share taps by one person are two); the activation page counts people,
 * from {@code invite_prompts}.
 */
@Component
public class InvitePromptMetrics {

    private final MeterRegistry registry;

    public InvitePromptMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void event(InvitePromptEvent event, ClientPlatform platform) {
        Counter.builder("finora.invite_prompt.events")
                .description("The invite-a-friend card after an import: shown, Share tapped, Not now tapped")
                .tag("event", event.name().toLowerCase())
                .tag("platform", platform.name().toLowerCase())
                .register(registry)
                .increment();
    }
}
