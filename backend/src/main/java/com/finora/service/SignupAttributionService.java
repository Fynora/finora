package com.finora.service;

import com.finora.dto.AuthDtos.SignupAttributionRequest;
import com.finora.entity.SignupAttribution;
import com.finora.repository.SignupAttributionRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records where a self-service sign-up came from (V286). The browser sends what it captured on
 * its first page of the visit ({@code frontend/src/lib/signupAttribution.ts}); this is the one
 * place that decides what of it is stored, so the client's own trimming is a courtesy, not a
 * guarantee:
 * <ul>
 *   <li>the referrer is reduced to a host name -- a full URL, with its path and query, is cut
 *       down here even if a client sends one, since a referrer URL can carry another site's
 *       tokens; anything that is not a plausible host name is dropped;</li>
 *   <li>each UTM value is trimmed, stripped of control characters, lower-cased and cut to
 *       {@value #UTM_MAX_LENGTH} characters;</li>
 *   <li>a request with nothing left after that writes no row at all, and the admin count reads
 *       the absence as "direct".</li>
 * </ul>
 * Never throws for bad input: attribution is a measurement, and a malformed one must not turn
 * into a failed registration.
 */
@Service
public class SignupAttributionService {

    static final int UTM_MAX_LENGTH = 100;
    static final int HOST_MAX_LENGTH = 253;

    /** Letters, digits, dots and hyphens only, as a host name is; no IDN punycode concern since
     *  browsers hand {@code document.referrer} over already ASCII-encoded. */
    private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");

    private final SignupAttributionRepository repository;

    public SignupAttributionService(SignupAttributionRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes the row for a just-created account. Joins the caller's registration transaction, so
     * a sign-up that rolls back leaves no attribution behind either.
     *
     * @param attribution what the client sent; {@code null} (the mobile apps, older web bundles)
     *                    writes nothing
     */
    public void record(UUID userId, SignupAttributionRequest attribution) {
        if (attribution == null) return;
        String referrerHost = normalizeReferrerHost(attribution.referrerHost());
        String utmSource = normalizeUtm(attribution.utmSource());
        String utmMedium = normalizeUtm(attribution.utmMedium());
        String utmCampaign = normalizeUtm(attribution.utmCampaign());
        String utmContent = normalizeUtm(attribution.utmContent());
        if (referrerHost == null && utmSource == null && utmMedium == null
                && utmCampaign == null && utmContent == null) {
            return;
        }
        repository.save(new SignupAttribution(userId, referrerHost, utmSource, utmMedium, utmCampaign, utmContent));
    }

    /**
     * The host name and nothing else, lower-cased: {@code "https://www.Example.com:443/path?x=1"}
     * and {@code "www.example.com"} both become {@code "www.example.com"}. {@code null} for an
     * empty value, a value that is not a host name, or one longer than a host name can be.
     */
    static String normalizeReferrerHost(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        String host;
        if (value.contains("://")) {
            try {
                host = URI.create(value).getHost();
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (host == null) return null;
        } else {
            // A bare host, possibly with a port, path, query or fragment tacked on: keep what
            // comes before the first of those.
            int cut = value.length();
            for (char stop : new char[] {'/', '?', '#', ':'}) {
                int at = value.indexOf(stop);
                if (at >= 0 && at < cut) cut = at;
            }
            host = value.substring(0, cut);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.length() > HOST_MAX_LENGTH || !HOST.matcher(host).matches()) return null;
        return host;
    }

    /**
     * Trimmed, control characters removed, lower-cased and cut to {@value #UTM_MAX_LENGTH}
     * characters; {@code null} when nothing is left. Lower-cased so that {@code Instagram} and
     * {@code instagram} count as one source. Cut on a code point boundary, since Postgres counts
     * characters, not UTF-16 units, and a split surrogate pair would not be valid text.
     */
    static String normalizeUtm(String raw) {
        if (raw == null) return null;
        String value = CONTROL_CHARS.matcher(raw).replaceAll("").strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > UTM_MAX_LENGTH) {
            value = value.substring(0, value.offsetByCodePoints(0, UTM_MAX_LENGTH));
        }
        return value;
    }
}
