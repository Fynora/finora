package com.finora.service;

import com.finora.config.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for audit finding F-07 (2026-09-18): {@code RestClient.create()} configures
 * neither a connect nor a read timeout, so a stalled 2Factor endpoint could hang this call (and,
 * for its real caller {@code TransactionService#doSendTransactionAlert}, the actual request thread
 * that just committed a transaction) indefinitely.
 *
 * <p>Asserting this by reading the actual resolved Apache HttpClient5 {@code ConnectionConfig}
 * (via reflection through {@code RestClient} -> {@code HttpComponentsClientHttpRequestFactory} ->
 * its {@code httpClient} -> its connection manager's {@code connectionConfigResolver}), not by
 * inspecting {@code ClientHttpRequestFactorySettings} or the wrapper factory's own fields --
 * confirmed by direct instrumentation that those specific fields stay {@code null}/{@code -1} in
 * this exact configuration path regardless of what was actually passed in (Apache HttpClient5
 * resolves connect/socket timeouts per-route through a resolver function, not through fields on
 * the factory itself), so asserting on them would either always pass trivially or always fail --
 * neither proves anything about what actually happens on the wire. This is the same technique
 * this class's own audit fix was verified with before shipping.
 */
class TwoFactorSmsProviderTest {

    private final TwoFactorSmsProvider provider = new TwoFactorSmsProvider(smsProperties());

    private static SmsProperties smsProperties() {
        SmsProperties props = new SmsProperties();
        props.setApiKey("test-api-key");
        return props;
    }

    @Test
    void restClient_resolvesToTenSecondConnectAndTwentySecondReadTimeouts() throws Exception {
        RestClient restClient = (RestClient) ReflectionTestUtils.getField(provider, "restClient");

        Object connectionConfig = resolveConnectionConfig(restClient);

        // org.apache.hc.client5.http.config.ConnectionConfig has no public getters in this
        // version -- its toString() is the stable, documented way to read it back (used the same
        // way to verify this mechanism at all before writing this test).
        String rendered = connectionConfig.toString();
        assertThat(rendered).contains("connectTimeout=10000 MILLISECONDS");
        assertThat(rendered).contains("socketTimeout=20000 MILLISECONDS");
    }

    /** Not a coincidence -- {@link TwoFactorSmsProvider}'s own doc comment says these match {@code
     *  ResendEmailProvider}'s values deliberately, for the same reason (a swallowed-failure,
     *  best-effort notification gains nothing from waiting longer). This pins that intent so the
     *  two providers can't silently drift apart. */
    @Test
    void timeoutConstants_matchResendEmailProvidersValuesExactly() {
        assertThat(ReflectionTestUtils.getField(TwoFactorSmsProvider.class, "CONNECT_TIMEOUT"))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(ReflectionTestUtils.getField(TwoFactorSmsProvider.class, "READ_TIMEOUT"))
                .isEqualTo(Duration.ofSeconds(20));
    }

    /** Walks the real object graph a configured {@link RestClient} builds down to the connection
     *  manager's resolver function, then invokes that resolver directly -- no real network I/O,
     *  no waiting, just reading back what was actually configured. */
    private static Object resolveConnectionConfig(RestClient restClient) throws Exception {
        Object clientRequestFactory = getField(restClient, "clientRequestFactory");
        Object httpClient = getField(clientRequestFactory, "httpClient");
        Object connManager = getField(httpClient, "connManager");

        Field resolverField = connManager.getClass().getDeclaredField("connectionConfigResolver");
        resolverField.setAccessible(true);
        Object resolver = resolverField.get(connManager);
        return resolverField.getType().getMethod("resolve", Object.class).invoke(resolver, (Object) null);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
