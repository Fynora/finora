package com.finora.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The alert must actually reach Sentry, through the production Sentry configuration and its
 * scrubber, not merely be called. So Sentry is initialised here with {@link MonitoringConfig}'s own
 * options, and its scrubbing {@code beforeSend} is wrapped to record what would be sent and then
 * drop it: nothing leaves the machine.
 *
 * <p>{@link Isolated}: the Sentry SDK is process-global, and unit test classes run concurrently.
 */
@Isolated
class PushTokenRevocationsTest {

    private final List<SentryEvent> sent = new CopyOnWriteArrayList<>();
    private SimpleMeterRegistry registry;
    private PushTokenRevocations revocations;

    @BeforeEach
    void setUp() {
        Sentry.OptionsConfiguration<SentryOptions> production =
                new MonitoringConfig("https://key@example.invalid/1", "production", "test").finoraSentryOptions();
        Sentry.init(options -> {
            // In the app the Spring starter sets the DSN; MonitoringConfig only checks it is present.
            options.setDsn("https://key@example.invalid/1");
            production.configure(options);
            SentryOptions.BeforeSendCallback scrubber = options.getBeforeSend();
            options.setBeforeSend((event, hint) -> {
                SentryEvent scrubbed = scrubber.execute(event, hint);
                if (scrubbed != null) {
                    sent.add(scrubbed);
                }
                return null;
            });
        });
        registry = new SimpleMeterRegistry();
        revocations = new PushTokenRevocations(registry);
    }

    @AfterEach
    void tearDown() {
        Sentry.close();
    }

    @Test
    void anInvalidArgumentRevocation_isCountedAndReachesSentryAsAnError() {
        UUID notificationId = UUID.randomUUID();

        revocations.invalidArgument(notificationId);

        assertThat(count("invalid_argument")).isEqualTo(1.0);
        assertThat(sent).hasSize(1);
        SentryEvent event = sent.get(0);
        assertThat(event.getLevel()).isEqualTo(SentryLevel.ERROR);
        assertThat(event.getMessage().getFormatted()).isEqualTo(PushTokenRevocations.ALERT_MESSAGE);
        // Allowlisted tags only, so they survive the scrubber.
        assertThat(event.getTag("phase")).isEqualTo("push-send");
        assertThat(event.getTag("outcome")).isEqualTo("token-revoked-invalid-argument");
        assertThat(event.getTag("jobId")).isEqualTo(notificationId.toString());
    }

    @Test
    void manyInvalidArgumentRevocations_raiseTheSameMessageEachTime_soSentryGroupsThem() {
        revocations.invalidArgument(UUID.randomUUID());
        revocations.invalidArgument(UUID.randomUUID());
        revocations.invalidArgument(UUID.randomUUID());

        assertThat(count("invalid_argument")).isEqualTo(3.0);
        assertThat(sent).extracting(e -> e.getMessage().getFormatted()).containsOnly(PushTokenRevocations.ALERT_MESSAGE);
    }

    @Test
    void anUnregisteredRevocation_isCountedButNeverAlerts() {
        revocations.unregistered();
        revocations.unregistered();

        assertThat(count("unregistered")).isEqualTo(2.0);
        assertThat(sent).isEmpty();
    }

    private double count(String reason) {
        var counter = registry.find(PushTokenRevocations.METRIC).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
