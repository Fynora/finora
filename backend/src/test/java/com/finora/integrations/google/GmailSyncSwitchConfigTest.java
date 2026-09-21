package com.finora.integrations.google;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pause switch is one variable ({@code GMAIL_SYNC_ENABLED}) that must stop the background sync
 * too, without a second variable to remember. That rests on a nested default in application.yml --
 * {@code ${GMAIL_DISCOVERY_ENABLED:${GMAIL_SYNC_ENABLED:true}}} -- which is exactly the kind of line
 * a typo silently breaks, so this resolves the REAL file against a controlled environment.
 *
 * <p>Loads only the main {@code application.yml}: not the {@code test} profile, which forces the
 * worker off for every integration test and would hide the thing being checked, and not the machine's
 * own environment variables, which would make the result depend on whoever runs it.
 */
class GmailSyncSwitchConfigTest {

    private static final String FEATURE = "app.integrations.google.enabled";
    private static final String WORKER = "app.integrations.google.discovery.enabled";

    private static String resolve(String property, Map<String, Object> environmentVariables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("controlled-env", environmentVariables));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application-yml", new ClassPathResource("application.yml"));
        yaml.forEach(source -> environment.getPropertySources().addLast(source));
        return environment.getProperty(property);
    }

    @Test
    @DisplayName("nothing set: the feature and its background sync are both on, so this change alters nothing until the variable is set")
    void defaultsAreOn() throws IOException {
        assertThat(resolve(FEATURE, Map.of())).isEqualTo("true");
        assertThat(resolve(WORKER, Map.of())).isEqualTo("true");
    }

    @Test
    @DisplayName("GMAIL_SYNC_ENABLED=false pauses the feature AND the background sync, from that one variable")
    void oneVariablePausesBoth() throws IOException {
        Map<String, Object> paused = Map.of("GMAIL_SYNC_ENABLED", "false");

        assertThat(resolve(FEATURE, paused)).isEqualTo("false");
        assertThat(resolve(WORKER, paused)).isEqualTo("false");
    }

    @Test
    @DisplayName("GMAIL_SYNC_ENABLED=true is the same as not setting it")
    void explicitlyOnIsOn() throws IOException {
        Map<String, Object> on = Map.of("GMAIL_SYNC_ENABLED", "true");

        assertThat(resolve(FEATURE, on)).isEqualTo("true");
        assertThat(resolve(WORKER, on)).isEqualTo("true");
    }

    @Test
    @DisplayName("the older worker-only variable still works on its own and leaves the feature switch alone")
    void workerVariableStillWorksAlone() throws IOException {
        Map<String, Object> workerOff = Map.of("GMAIL_DISCOVERY_ENABLED", "false");

        assertThat(resolve(WORKER, workerOff)).isEqualTo("false");
        assertThat(resolve(FEATURE, workerOff)).isEqualTo("true");
    }

    @Test
    @DisplayName("an explicit GMAIL_DISCOVERY_ENABLED wins over the pause switch")
    void explicitWorkerVariableOverridesThePauseSwitch() throws IOException {
        Map<String, Object> both = Map.of("GMAIL_SYNC_ENABLED", "false", "GMAIL_DISCOVERY_ENABLED", "true");

        assertThat(resolve(FEATURE, both)).isEqualTo("false");
        assertThat(resolve(WORKER, both)).isEqualTo("true");
    }
}
