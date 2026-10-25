package com.finora.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClientIpResolver directly. Addresses are RFC 5737 documentation addresses (192.0.2.0/24,
 * 198.51.100.0/24, 203.0.113.0/24), RFC 6598 shared space for proxy hops, and addresses inside
 * Cloudflare's published ranges; none is a real visitor's.
 *
 * <p>The Railway shape used throughout ("client, railway-hop", two entries, the caller's own
 * X-Forwarded-For replaced) is what production showed on 2026-10-25; see ClientIpResolver.
 */
class ClientIpResolverTest {

    private static final String PROXY_PEER = "10.0.0.1";
    private static final String RAILWAY_HOP = "100.64.12.34";
    private static final String VISITOR = "203.0.113.9";
    private static final String OTHER_VISITOR = "198.51.100.7";
    private static final String FORGED = "192.0.2.66";
    private static final String CLOUDFLARE_EDGE = "162.158.1.1";      // inside 162.158.0.0/15
    private static final String CLOUDFLARE_EDGE_V6 = "2606:4700:10::1"; // inside 2606:4700::/32

    private ClientIpResolver resolverWith(boolean trustProxyHeaders, int trustedProxyHops) {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustProxyHeaders", trustProxyHeaders);
        ReflectionTestUtils.setField(resolver, "trustedProxyHops", trustedProxyHops);
        return resolver;
    }

    private MockHttpServletRequest request(String forwardedFor, String cfConnectingIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        request.setRemoteAddr(PROXY_PEER);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        if (cfConnectingIp != null) request.addHeader("CF-Connecting-IP", cfConnectingIp);
        return request;
    }

    private static String chain(String... entries) {
        return String.join(", ", entries);
    }

    // --- trust off ---------------------------------------------------------------------------

    @Test
    void usesRemoteAddr_whenProxyHeadersAreNotTrusted() {
        ClientIpResolver resolver = resolverWith(false, 2);

        assertThat(resolver.resolve(request(chain(VISITOR, RAILWAY_HOP), VISITOR))).isEqualTo(PROXY_PEER);
    }

    @Test
    void ignoresCfConnectingIp_whenProxyHeadersAreNotTrusted_evenFromACloudflareLookingPeer() {
        ClientIpResolver resolver = resolverWith(false, 2);
        MockHttpServletRequest request = request(null, FORGED);
        request.setRemoteAddr(CLOUDFLARE_EDGE);

        assertThat(resolver.resolve(request)).isEqualTo(CLOUDFLARE_EDGE);
    }

    // --- Railway, two entries ------------------------------------------------------------------

    @Test
    void railway_takesTheEntryBeforeRailwaysOwnHop() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(chain(VISITOR, RAILWAY_HOP), null))).isEqualTo(VISITOR);
    }

    @Test
    void railway_neverKeysTwoVisitorsOnTheSharedHop() {
        ClientIpResolver resolver = resolverWith(true, 2);

        String first = resolver.resolve(request(chain(VISITOR, RAILWAY_HOP), null));
        String second = resolver.resolve(request(chain(OTHER_VISITOR, RAILWAY_HOP), null));

        assertThat(first).isNotEqualTo(second).isNotEqualTo(RAILWAY_HOP);
    }

    /** The bug this change fixes: with one hop configured, every Railway request resolved to
     *  Railway's own hop, so all visitors shared one bucket per hop. */
    @Test
    void oneHopOnRailwaysTwoEntryChain_picksTheRailwayHop_whichIsWhyTheDefaultIsNowTwo() {
        ClientIpResolver resolver = resolverWith(true, 1);

        assertThat(resolver.resolve(request(chain(VISITOR, RAILWAY_HOP), null))).isEqualTo(RAILWAY_HOP);
    }

    // --- Cloudflare ----------------------------------------------------------------------------

    @Test
    void cloudflare_readsCfConnectingIp_whenTheProxyReportedClientIsACloudflareEdge() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(chain(CLOUDFLARE_EDGE, RAILWAY_HOP), VISITOR))).isEqualTo(VISITOR);
    }

    @Test
    void cloudflare_readsCfConnectingIp_forAnIpv6CloudflareEdgeAndAnIpv6Visitor() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(chain(CLOUDFLARE_EDGE_V6, RAILWAY_HOP), "2001:db8::9")))
                .isEqualTo("2001:db8::9");
    }

    @Test
    void cloudflare_fallsBackToTheEdge_whenCfConnectingIpIsMissing() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(chain(CLOUDFLARE_EDGE, RAILWAY_HOP), null))).isEqualTo(CLOUDFLARE_EDGE);
    }

    @Test
    void cloudflare_fallsBackToTheEdge_whenCfConnectingIpIsNotAnAddress() {
        ClientIpResolver resolver = resolverWith(true, 2);

        for (String junk : new String[]{"", "   ", "unknown", "attacker.example", "999.1.1.1", "1.2.3",
                "1.2.3.4.5", "203.0.113.9, 198.51.100.7", "fe80::1%eth0", "[2001:db8::1]"}) {
            assertThat(resolver.resolve(request(chain(CLOUDFLARE_EDGE, RAILWAY_HOP), junk)))
                    .as("CF-Connecting-IP %s", junk)
                    .isEqualTo(CLOUDFLARE_EDGE);
        }
    }

    // --- spoofing ------------------------------------------------------------------------------

    /** A caller that skips Cloudflare and reaches the origin directly arrives with its own real
     *  address as the proxy-reported client. Whatever CF-Connecting-IP it sends must be ignored. */
    @Test
    void spoof_forgedCfConnectingIpFromANonCloudflareClientIsIgnored() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(chain(VISITOR, RAILWAY_HOP), FORGED))).isEqualTo(VISITOR);
    }

    /** A fresh forged value on every request must not give the caller a fresh bucket each time. */
    @Test
    void spoof_rotatingForgedCfConnectingIpAlwaysResolvesToTheCallersOwnAddress() {
        ClientIpResolver resolver = resolverWith(true, 2);

        for (int i = 1; i <= 20; i++) {
            assertThat(resolver.resolve(request(chain(VISITOR, RAILWAY_HOP), "192.0.2." + i))).isEqualTo(VISITOR);
        }
    }

    /** On a proxy that appends instead of replacing, entries before the trusted hops are the
     *  caller's own. They must never be read, including a forged Cloudflare edge placed there to
     *  unlock CF-Connecting-IP. */
    @Test
    void spoof_leadingEntriesTheCallerAddedAreNeverRead_evenACloudflareLookingOne() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(chain(CLOUDFLARE_EDGE, VISITOR, RAILWAY_HOP), FORGED)))
                .isEqualTo(VISITOR);
        assertThat(resolver.resolve(request(chain(FORGED, VISITOR, RAILWAY_HOP), null))).isEqualTo(VISITOR);
    }

    // --- hop count edge cases ------------------------------------------------------------------

    @Test
    void takesTheEntryBeforeTheTrustedHops_forThreeHops() {
        ClientIpResolver resolver = resolverWith(true, 3);

        assertThat(resolver.resolve(request(chain(FORGED, VISITOR, "10.0.0.5", RAILWAY_HOP), null)))
                .isEqualTo(VISITOR);
    }

    @Test
    void fallsBackToRemoteAddr_whenFewerEntriesThanTrustedHops() {
        ClientIpResolver resolver = resolverWith(true, 2);

        // One entry where Railway always writes two: not a header Railway wrote, so the single
        // entry may be the caller's own and is not trusted.
        assertThat(resolver.resolve(request(VISITOR, null))).isEqualTo(PROXY_PEER);
    }

    @Test
    void fallsBackToRemoteAddr_whenTheHopCountIsZeroOrNegative() {
        assertThat(resolverWith(true, 0).resolve(request(chain(VISITOR, RAILWAY_HOP), null))).isEqualTo(PROXY_PEER);
        assertThat(resolverWith(true, -1).resolve(request(chain(VISITOR, RAILWAY_HOP), null))).isEqualTo(PROXY_PEER);
    }

    @Test
    void fallsBackToRemoteAddr_whenTheHeaderIsAbsentBlankOrTheClientEntryIsEmpty() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request(null, null))).isEqualTo(PROXY_PEER);
        assertThat(resolver.resolve(request("   ", null))).isEqualTo(PROXY_PEER);
        assertThat(resolver.resolve(request(" , " + RAILWAY_HOP, null))).isEqualTo(PROXY_PEER);
    }

    @Test
    void trimsWhitespaceAroundEntries() {
        ClientIpResolver resolver = resolverWith(true, 2);

        assertThat(resolver.resolve(request("  " + VISITOR + "  ,  " + RAILWAY_HOP + " ", null))).isEqualTo(VISITOR);
        assertThat(resolver.resolve(request(chain(CLOUDFLARE_EDGE, RAILWAY_HOP), "  " + VISITOR + " ")))
                .isEqualTo(VISITOR);
    }

    // --- address checks ------------------------------------------------------------------------

    @Test
    void isCloudflare_recognisesPublishedRangesAndRejectsEverythingElse() {
        assertThat(ClientIpResolver.isCloudflare("162.158.1.1")).isTrue();
        assertThat(ClientIpResolver.isCloudflare("104.16.0.0")).isTrue();
        assertThat(ClientIpResolver.isCloudflare("172.71.255.255")).isTrue(); // last of 172.64.0.0/13
        assertThat(ClientIpResolver.isCloudflare("172.72.0.0")).isFalse();   // one past it
        assertThat(ClientIpResolver.isCloudflare("2606:4700::1")).isTrue();
        assertThat(ClientIpResolver.isCloudflare("2001:db8::1")).isFalse();
        assertThat(ClientIpResolver.isCloudflare(VISITOR)).isFalse();
        assertThat(ClientIpResolver.isCloudflare(RAILWAY_HOP)).isFalse();
        assertThat(ClientIpResolver.isCloudflare(null)).isFalse();
        assertThat(ClientIpResolver.isCloudflare("")).isFalse();
        assertThat(ClientIpResolver.isCloudflare("cloudflare.com")).isFalse();
    }

    @Test
    void isIpLiteral_acceptsOnlyRealLiterals_soNothingIsEverLookedUpAsAHostname() {
        assertThat(ClientIpResolver.isIpLiteral("203.0.113.9")).isTrue();
        assertThat(ClientIpResolver.isIpLiteral("0.0.0.0")).isTrue();
        assertThat(ClientIpResolver.isIpLiteral("255.255.255.255")).isTrue();
        assertThat(ClientIpResolver.isIpLiteral("2001:db8::9")).isTrue();
        assertThat(ClientIpResolver.isIpLiteral("::ffff:203.0.113.9")).isTrue();

        assertThat(ClientIpResolver.isIpLiteral("256.0.0.1")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("999.1.1.1")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("01.2.3.4")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("1.2.3")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("abc.def")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("2001:db8::zz")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral("1:2:3:4:5:6:7:8:9")).isFalse();
        assertThat(ClientIpResolver.isIpLiteral(null)).isFalse();
    }
}
