package com.finora.observability;

import com.finora.config.ClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Synthetic documentation-range and Cloudflare-range addresses only; no real visitor values. */
class ClientIpDiagnosticsTest {

    private static final String VISITOR = "203.0.113.7";       // RFC 5737 documentation range
    private static final String OTHER_VISITOR = "198.51.100.9"; // RFC 5737 documentation range
    private static final String CLOUDFLARE_EDGE = "162.158.1.1"; // inside 162.158.0.0/15

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ClientIpDiagnostics diagnostics = new ClientIpDiagnostics(resolver(), registry);

    private static ClientIpResolver resolver() {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustProxyHeaders", true);
        ReflectionTestUtils.setField(resolver, "trustedProxyHops", 1);
        return resolver;
    }

    private static MockHttpServletRequest request(String host, String forwardedFor, String cfConnectingIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.setRemoteAddr("10.0.0.1");
        if (host != null) request.addHeader("Host", host);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        if (cfConnectingIp != null) request.addHeader("CF-Connecting-IP", cfConnectingIp);
        return request;
    }

    @Test
    void lastHopIsACloudflareEdgeIsLabelledAsSuch() {
        ClientIpDiagnostics.Labels labels = diagnostics.classify(
                request("api.fynora.net", VISITOR + ", " + CLOUDFLARE_EDGE, VISITOR));

        assertThat(labels).isEqualTo(new ClientIpDiagnostics.Labels("api", "2", "true", "differs", "differs"));
    }

    @Test
    void lastHopIsTheVisitorIsLabelledAsAMatch() {
        ClientIpDiagnostics.Labels labels = diagnostics.classify(request("api.fynora.net", VISITOR, VISITOR));

        assertThat(labels).isEqualTo(new ClientIpDiagnostics.Labels("api", "1", "false", "match", "match"));
    }

    @Test
    void noForwardingHeadersFallBackToRemoteAddressAndSayAbsent() {
        ClientIpDiagnostics.Labels labels = diagnostics.classify(request("api.fynora.net", null, null));

        assertThat(labels).isEqualTo(new ClientIpDiagnostics.Labels("api", "0", "false", "absent", "absent"));
    }

    @Test
    void threeOrMoreHopsAreBucketedAndOtherHostsAreLabelledOther() {
        ClientIpDiagnostics.Labels labels = diagnostics.classify(
                request("hooks.fynora.net:443", OTHER_VISITOR + ", " + VISITOR + ", " + CLOUDFLARE_EDGE + ", 10.1.1.1", null));

        assertThat(labels.hops()).isEqualTo("3+");
        assertThat(labels.host()).isEqualTo("other");
    }

    @Test
    void hostWithPortAndUpperCaseStillCountsAsTheApiHost() {
        assertThat(diagnostics.classify(request("API.Fynora.net:443", VISITOR, null)).host()).isEqualTo("api");
    }

    @Test
    void cloudflareIpv6RangesAreRecognised() {
        assertThat(ClientIpDiagnostics.isCloudflare("2606:4700::1")).isTrue();
        assertThat(ClientIpDiagnostics.isCloudflare("2001:db8::1")).isFalse();
    }

    @Test
    void nonIpValuesAreNeverCloudflareAndNeverThrow() {
        assertThat(ClientIpDiagnostics.isCloudflare("unknown")).isFalse();
        assertThat(ClientIpDiagnostics.isCloudflare("example.com")).isFalse();
        assertThat(ClientIpDiagnostics.isCloudflare("1.2.3")).isFalse();
        assertThat(ClientIpDiagnostics.isCloudflare("")).isFalse();
        assertThat(ClientIpDiagnostics.isCloudflare(null)).isFalse();
    }

    @Test
    void malformedForwardingHeaderIsRecordedWithoutThrowing() {
        assertThatCode(() -> diagnostics.record(request("api.fynora.net", " , ,garbage", "not-an-ip")))
                .doesNotThrowAnyException();
        assertThat(registry.find("finora.client_ip.resolution").counter()).isNotNull();
    }

    @Test
    void recordCountsByLabelsAndTheSummaryNeverContainsAnAddress() {
        diagnostics.record(request("api.fynora.net", VISITOR + ", " + CLOUDFLARE_EDGE, VISITOR));
        diagnostics.record(request("api.fynora.net", OTHER_VISITOR + ", " + CLOUDFLARE_EDGE, OTHER_VISITOR));

        assertThat(registry.get("finora.client_ip.resolution")
                .tag("host", "api").tag("hops", "2").tag("resolved_cloudflare", "true")
                .tag("cf_connecting_ip", "differs").tag("first_hop", "differs")
                .counter().count()).isEqualTo(2.0);

        String summary = diagnostics.drainSummary();
        assertThat(summary).contains("resolved_cloudflare=true").contains("=2");
        assertThat(summary).doesNotContain(VISITOR).doesNotContain(OTHER_VISITOR).doesNotContain(CLOUDFLARE_EDGE);
    }

    @Test
    void summaryResetsAfterEachDrainAndIsNullWhenEmpty() {
        diagnostics.record(request("api.fynora.net", VISITOR, VISITOR));

        assertThat(diagnostics.drainSummary()).isNotNull();
        assertThat(diagnostics.drainSummary()).isNull();
    }
}
