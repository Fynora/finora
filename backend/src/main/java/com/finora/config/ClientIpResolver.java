package com.finora.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the real client IP for a request. The single implementation: RateLimitFilter buckets
 * rate limits by it, RefreshTokenService records it on device sessions (RefreshToken.lastSeenIp)
 * and RequestMetadata puts it on audit entries.
 *
 * <p>request.getRemoteAddr() alone only returns the real client IP when nothing sits between the
 * client and this app. Behind Railway every request arrives from Railway's own proxy, so
 * X-Forwarded-For is read instead, but only when app.security.trust-proxy-headers is enabled.
 * Entries a client sent itself must never be trusted: a proxy that appends leaves everything before
 * its own entry under the caller's control, and an address the caller picks lets it dodge every
 * per-address rate limit.
 *
 * <p><b>Which entry.</b> {@code trustedProxyHops} counts the trailing X-Forwarded-For entries that
 * belong to the proxy chain itself; the entry just before them is the client as the outermost
 * trusted proxy saw it. Measured on Railway production on 2026-10-25 (labels only, no addresses
 * recorded): every request arrives with exactly two entries, also when the caller sent its own
 * X-Forwarded-For (Railway's edge replaces the header rather than appending to it), and the last
 * entry is a Railway infrastructure hop, not the caller. So Railway needs 2, and the old default
 * of 1 keyed every rate limit on Railway's own hop.
 *
 * <p><b>Cloudflare.</b> When the client the proxy chain reports is itself a Cloudflare edge, the
 * visitor's address is in CF-Connecting-IP, which Cloudflare sets and overwrites. That header is
 * read only in that case. It is never read on its own: the origin can be reached without going
 * through Cloudflare, and any caller who does that can send whatever CF-Connecting-IP it likes.
 * A request whose proxy-reported client is not a Cloudflare address did not come through
 * Cloudflare, so its CF-Connecting-IP is ignored.
 *
 * <p>Too few entries for the configured hop count (a misconfiguration, or a header a proxy did not
 * write) falls back to getRemoteAddr() rather than guessing at an entry that may be the caller's
 * own.
 */
@Component
public class ClientIpResolver {

    /** Cloudflare's published ranges, https://www.cloudflare.com/ips-v4 and /ips-v6, fetched
     *  2026-10-23. Used only to decide whether CF-Connecting-IP may be read. If Cloudflare adds a
     *  range and it is missing here, visitors arriving through it are keyed on the Cloudflare edge
     *  address instead (shared, but never caller-chosen); nothing becomes forgeable. */
    static final List<String> CLOUDFLARE_RANGES = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32");

    private static final List<IpAddressMatcher> CLOUDFLARE_MATCHERS =
            CLOUDFLARE_RANGES.stream().map(IpAddressMatcher::new).toList();

    /** Digits, hex letters, dots and colons only: an IPv4 or IPv6 literal, never a hostname, so
     *  nothing below can trigger a DNS lookup. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4_LITERAL = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    @Value("${app.security.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    /** Initialised here as well as in @Value, so a resolver built without Spring (tests) does not
     *  silently sit at Java's int default of 0. */
    @Value("${app.security.trusted-proxy-hops:2}")
    private int trustedProxyHops = 2;

    public String resolve(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] entries = forwardedFor.split(",");
                int clientIndex = entries.length - trustedProxyHops;
                if (trustedProxyHops >= 1 && clientIndex >= 0) {
                    String client = entries[clientIndex].trim();
                    if (isCloudflare(client)) {
                        String visitor = request.getHeader("CF-Connecting-IP");
                        if (visitor != null && isIpLiteral(visitor.trim())) {
                            return visitor.trim();
                        }
                    }
                    if (!client.isEmpty()) {
                        return client;
                    }
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** Whether {@code ip} is an IP literal inside one of Cloudflare's published ranges. */
    public static boolean isCloudflare(String ip) {
        if (ip == null || !isIpLiteral(ip.trim())) return false;
        String candidate = ip.trim();
        for (IpAddressMatcher matcher : CLOUDFLARE_MATCHERS) {
            // A matcher of the other address family answers false rather than throwing.
            if (matcher.matches(candidate)) return true;
        }
        return false;
    }

    /** A strict IPv4 dotted quad, or an IPv6 literal. Checked before any value reaches
     *  InetAddress: an IPv4-looking string it cannot parse (such as 999.1.1.1) would otherwise be
     *  tried as a hostname, while a string containing a colon is only ever parsed as IPv6. */
    public static boolean isIpLiteral(String value) {
        if (value == null || !IP_LITERAL.matcher(value).matches()) return false;
        if (value.indexOf(':') < 0) return IPV4_LITERAL.matcher(value).matches();
        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
