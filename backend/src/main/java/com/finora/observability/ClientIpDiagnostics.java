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

/**
 * Temporary evidence-gathering for one question: does {@link ClientIpResolver} hand rate limiting,
 * audit metadata and refresh-token sessions the visitor's real IP, or the address of a proxy in
 * front of the app? If it is a proxy, unrelated users share one rate-limit bucket.
 *
 * <p>The first reading (2026-10-25) answered it for the old one-hop setting: every request carried
 * two X-Forwarded-For entries and the resolver picked the second, a Railway infrastructure hop.
 * The resolver now reads the entry before that hop, and CF-Connecting-IP only when that entry is a
 * Cloudflare edge. This class stays until that is confirmed in production: after the fix deploys,
 * ordinary traffic should read {@code cf_connecting_ip=match}, and test requests carrying forged
 * RFC 5737 values must never read {@code resolved_documentation_range=true}.
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
 *       entry ({@code match}/{@code differs}/{@code absent});</li>
 *   <li>{@code resolved_documentation_range}: whether the resolver's pick is an RFC 5737
 *       documentation address ({@code true}/{@code false}). No visitor has one, so {@code true}
 *       means a forged value got through.</li>
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

    /** RFC 5737 documentation ranges. No real visitor has one of these, so a resolved address
     *  inside them can only be a value a test request forged (192.0.2.0/24, 198.51.100.0/24,
     *  203.0.113.0/24). Lets a forged-header probe against production show, by label alone,
     *  whether a forged value got through. */
    private static final List<IpAddressMatcher> DOCUMENTATION_RANGES = List.of(
            new IpAddressMatcher("192.0.2.0/24"), new IpAddressMatcher("198.51.100.0/24"),
            new IpAddressMatcher("203.0.113.0/24"));

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
                    .tag("resolved_documentation_range", labels.resolvedDocumentationRange())
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
                compare(resolved, hops.length == 0 ? null : hops[0]),
                Boolean.toString(isDocumentationRange(resolved)));
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
        return ClientIpResolver.isCloudflare(ip);
    }

    static boolean isDocumentationRange(String ip) {
        if (ip == null) return false;
        String candidate = ip.trim();
        // IPv4 literals only (the ranges are IPv4), checked strictly first so the matcher never
        // sees a string it could try to look up as a hostname.
        if (candidate.indexOf(':') >= 0 || !ClientIpResolver.isIpLiteral(candidate)) return false;
        for (IpAddressMatcher matcher : DOCUMENTATION_RANGES) {
            if (matcher.matches(candidate)) return true;
        }
        return false;
    }

    private static String compare(String resolved, String other) {
        if (other == null || other.isBlank()) return "absent";
        return other.trim().equals(resolved == null ? null : resolved.trim()) ? "match" : "differs";
    }

    record Labels(String host, String hops, String resolvedCloudflare, String cfConnectingIp, String firstHop,
                  String resolvedDocumentationRange) {
        String key() {
            return "host=" + host + ",hops=" + hops + ",resolved_cloudflare=" + resolvedCloudflare
                    + ",cf_connecting_ip=" + cfConnectingIp + ",first_hop=" + firstHop
                    + ",resolved_documentation_range=" + resolvedDocumentationRange;
        }
    }
}
