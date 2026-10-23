package com.finora.observability;

import com.finora.config.ClientIpResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Temporary evidence-gathering for one question: does {@link ClientIpResolver} hand rate limiting,
 * audit metadata and refresh-token sessions the visitor's real IP, or the address of a proxy in
 * front of the app (for example a Cloudflare edge)? If it is a proxy, unrelated users share one
 * rate-limit bucket. Railway's own HTTP log records the real visitor IP for traffic that came
 * through Cloudflare, but that does not show what reaches the app in {@code X-Forwarded-For}.
 *
 * <p><b>Privacy:</b> no IP address is ever stored, logged or used as a metric tag. Each request is
 * reduced to a few bounded labels:
 * <ul>
 *   <li>{@code host}: {@code api} for the public API host, {@code other} for anything else;</li>
 *   <li>{@code hops}: how many entries {@code X-Forwarded-For} has ({@code 0}, {@code 1},
 *       {@code 2}, {@code 3+});</li>
 *   <li>{@code resolved_cloudflare}: whether the IP the resolver picked is in Cloudflare's
 *       published ranges ({@code true}/{@code false});</li>
 *   <li>{@code cf_connecting_ip}: whether the resolver's pick equals the {@code CF-Connecting-IP}
 *       header ({@code match}/{@code differs}/{@code absent});</li>
 *   <li>{@code first_hop}: whether the resolver's pick equals the first {@code X-Forwarded-For}
 *       entry ({@code match}/{@code differs}/{@code absent}).</li>
 * </ul>
 * Counted as the {@code finora.client_ip.resolution} counter and summarised in one log line every
 * 15 minutes, so the answer can be read from either Grafana or the Railway log.
 *
 * <p>Remove this class and {@link com.finora.config.ClientIpDiagnosticsFilter} once the question is
 * answered and any resolver fix has shipped.
 */
@Component
public class ClientIpDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(ClientIpDiagnostics.class);

    static final String API_HOST = "api.fynora.net";

    /** Cloudflare's published ranges, https://www.cloudflare.com/ips-v4 and /ips-v6
     *  (fetched 2026-10-23). Only used to label a request; nothing is allowed or blocked by it. */
    static final List<String> CLOUDFLARE_RANGES = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32");

    /** Digits, hex letters, dots and colons only: an IPv4 or IPv6 literal, never a hostname. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private static final List<IpAddressMatcher> CLOUDFLARE_MATCHERS =
            CLOUDFLARE_RANGES.stream().map(IpAddressMatcher::new).toList();

    private final ClientIpResolver clientIpResolver;
    private final MeterRegistry registry;
    private final Map<String, LongAdder> sinceLastSummary = new ConcurrentHashMap<>();

    public ClientIpDiagnostics(ClientIpResolver clientIpResolver, MeterRegistry registry) {
        this.clientIpResolver = clientIpResolver;
        this.registry = registry;
    }

    /** Labels one request and counts it. Never throws: diagnostics must not affect a request. */
    public void record(HttpServletRequest request) {
        try {
            Labels labels = classify(request);
            Counter.builder("finora.client_ip.resolution")
                    .description("How the client IP was resolved for a request (labels only, no IPs)")
                    .tag("host", labels.host())
                    .tag("hops", labels.hops())
                    .tag("resolved_cloudflare", labels.resolvedCloudflare())
                    .tag("cf_connecting_ip", labels.cfConnectingIp())
                    .tag("first_hop", labels.firstHop())
                    .register(registry)
                    .increment();
            sinceLastSummary.computeIfAbsent(labels.key(), k -> new LongAdder()).increment();
        } catch (RuntimeException e) {
            log.debug("Client IP diagnostics skipped a request: {}", e.getClass().getSimpleName());
        }
    }

    Labels classify(HttpServletRequest request) {
        String resolved = clientIpResolver.resolve(request);
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String[] hops = forwardedFor == null || forwardedFor.isBlank() ? new String[0] : forwardedFor.split(",");
        String cfConnectingIp = request.getHeader("CF-Connecting-IP");

        return new Labels(
                hostLabel(request.getHeader("Host")),
                hops.length >= 3 ? "3+" : Integer.toString(hops.length),
                Boolean.toString(isCloudflare(resolved)),
                compare(resolved, cfConnectingIp),
                compare(resolved, hops.length == 0 ? null : hops[0]));
    }

    /** Logs the counts gathered since the previous summary, then starts again. Labels only. */
    @Scheduled(fixedDelayString = "${app.diagnostics.client-ip.summary-interval-ms:900000}",
            initialDelayString = "${app.diagnostics.client-ip.summary-interval-ms:900000}")
    public void logSummary() {
        String summary = drainSummary();
        if (summary != null) {
            log.info("Client IP diagnostics (last interval, labels only): {}", summary);
        }
    }

    /** The counts since the last call, as {@code labels=count} pairs, or null if there were none. */
    String drainSummary() {
        Map<String, Long> counts = new TreeMap<>();
        sinceLastSummary.forEach((key, adder) -> {
            long count = adder.sumThenReset();
            if (count > 0) counts.put(key, count);
        });
        return counts.isEmpty() ? null : counts.toString();
    }

    private static String hostLabel(String hostHeader) {
        if (hostHeader == null) return "other";
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon >= 0 && !host.startsWith("[")) host = host.substring(0, colon);
        return API_HOST.equals(host) ? "api" : "other";
    }

    static boolean isCloudflare(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String candidate = ip.trim();
        // Only IP literals reach the matcher: anything else (a hostname, "unknown") could make it
        // attempt a DNS lookup, which a per-request diagnostic must never do.
        if (!IP_LITERAL.matcher(candidate).matches()) return false;
        for (IpAddressMatcher matcher : CLOUDFLARE_MATCHERS) {
            try {
                if (matcher.matches(candidate)) return true;
            } catch (IllegalArgumentException e) {
                return false; // not an IP address at all
            }
        }
        return false;
    }

    private static String compare(String resolved, String other) {
        if (other == null || other.isBlank()) return "absent";
        return other.trim().equals(resolved == null ? null : resolved.trim()) ? "match" : "differs";
    }

    record Labels(String host, String hops, String resolvedCloudflare, String cfConnectingIp, String firstHop) {
        String key() {
            return "host=" + host + ",hops=" + hops + ",resolved_cloudflare=" + resolvedCloudflare
                    + ",cf_connecting_ip=" + cfConnectingIp + ",first_hop=" + firstHop;
        }
    }
}
