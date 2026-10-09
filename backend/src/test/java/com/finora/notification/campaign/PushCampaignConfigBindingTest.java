package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The runbook tells operators to raise the staged-rollout limit with the environment variable
 * {@code APP_PUSH_CAMPAIGNS_MAX_AUDIENCE}. {@code @Value("${app.push-campaigns.max-audience}")} reads
 * the Spring {@code Environment}, whose system-environment source maps such a variable to the dotted,
 * hyphenated property name. This checks that mapping with Spring's own classes, so the documented
 * variable names cannot silently stop working (a rollout limit that ignores its override would leave
 * a larger audience refused with no way to raise it).
 */
class PushCampaignConfigBindingTest {

    @Test
    void theDocumentedEnvironmentVariablesResolveToTheProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env", Map.of(
                "APP_PUSH_CAMPAIGNS_MAX_AUDIENCE", "1000",
                "APP_PUSH_CAMPAIGNS_DRAIN_MAX_SECONDS", "45",
                "APP_PUSH_CAMPAIGNS_PAGE_SIZE", "150",
                "APP_PUSH_CAMPAIGNS_SCHEDULER_ENABLED", "false")));

        assertThat(environment.getProperty("app.push-campaigns.max-audience")).isEqualTo("1000");
        assertThat(environment.getProperty("app.push-campaigns.drain-max-seconds")).isEqualTo("45");
        assertThat(environment.getProperty("app.push-campaigns.page-size")).isEqualTo("150");
        assertThat(environment.getProperty("app.push-campaigns.scheduler.enabled")).isEqualTo("false");
    }
}
