package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trusted-sender rows the migrations seed, checked through the real trust gate against the real
 * shape of Gmail's {@code Authentication-Results} for these merchants. V217 exists because the
 * domains real Swiggy, Instamart and Amazon Pay mail is authenticated as ({@code swiggy.in},
 * {@code instamart.in}, {@code amazonpay.in}) were not on the registry, so that mail was refused
 * before any template or parser could see it.
 */
class TrustedSenderSeedIT extends AbstractIntegrationTest {

    @Autowired private SenderAuthenticationService authentication;
    @Autowired private TrustedSenderDomainRepository domains;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    /** Tests in this suite share one Redis; a stale cached answer for a fixed domain would make
     *  these assertions depend on test order. */
    @BeforeEach
    void clearCachedAnswers() {
        Cache cache = cacheManager.getCache(CacheConfig.TRUSTED_SENDER_DOMAINS_CACHE);
        if (cache != null) {
            cache.clear();
        }
    }

    /** The clause layout Gmail wrote for these senders: DMARC pass for the From domain, a DKIM pass
     *  for that domain plus one for the mailer's own domain, and an SPF pass for the mailer. */
    private static String gmailVerdictFor(String fromDomain) {
        return "mx.google.com; dkim=pass header.i=@" + fromDomain + " header.s=selector;"
                + " dkim=pass header.i=@amazonses.com header.s=selector;"
                + " spf=pass (google.com: domain of mailer.example designates x as permitted sender)"
                + " smtp.mailfrom=mailer.example;"
                + " dmarc=pass (p=QUARANTINE sp=QUARANTINE dis=NONE) header.from=" + fromDomain;
    }

    @Test
    @DisplayName("the three domains real Swiggy, Instamart and Amazon Pay mail is authenticated as are trusted")
    void theThreeRealSenderDomainsAreTrusted() {
        for (String domain : new String[] {"swiggy.in", "instamart.in", "amazonpay.in"}) {
            SenderAuthenticationService.Result result = authentication.evaluate(gmailVerdictFor(domain));

            assertThat(result.verdict()).as(domain).isEqualTo(SenderAuthenticationService.Verdict.TRUSTED);
            assertThat(result.authenticatedDomain()).isEqualTo(domain);
        }
    }

    @Test
    @DisplayName("each seeded row is ACTIVE and labelled for display")
    void theRowsAreActive() {
        assertThat(domains.findByDomain("swiggy.in")).get()
                .satisfies(row -> {
                    assertThat(row.isActive()).isTrue();
                    assertThat(row.getMerchantName()).isEqualTo("Swiggy");
                });
        assertThat(domains.findByDomain("instamart.in")).get()
                .satisfies(row -> assertThat(row.isActive()).isTrue());
        assertThat(domains.findByDomain("amazonpay.in")).get()
                .satisfies(row -> assertThat(row.isActive()).isTrue());
    }

    @Test
    @DisplayName("trust is exact: a lookalike or a subdomain of a seeded domain is not trusted")
    void trustIsExactNotSuffix() {
        for (String domain : new String[] {
                "swiggy.in.attacker.example", "mail.swiggy.in", "notswiggy.in", "instamart.in.example",
                "amazonpay.in.example", "xamazonpay.in"}) {
            SenderAuthenticationService.Result result = authentication.evaluate(gmailVerdictFor(domain));

            assertThat(result.verdict()).as(domain)
                    .isEqualTo(SenderAuthenticationService.Verdict.DOMAIN_NOT_TRUSTED);
        }
    }

    @Test
    @DisplayName("a failed DMARC and DKIM for a seeded domain is still refused, whatever SPF says")
    void aSeededDomainStillNeedsAuthentication() {
        String spoofed = "mx.google.com; dkim=fail header.i=@swiggy.in header.s=selector;"
                + " spf=softfail smtp.mailfrom=attacker.example;"
                + " dmarc=fail (p=QUARANTINE sp=QUARANTINE dis=NONE) header.from=swiggy.in";

        assertThat(authentication.evaluate(spoofed).verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.NOT_AUTHENTICATED);
    }

    /**
     * An admin can add or disable a domain through the management endpoints, and the table has a
     * unique index on {@code domain}. Replaying the migration's own statement over an existing row
     * must neither fail (that would stop the backend booting) nor re-enable a domain an admin
     * deliberately disabled (that would undo a security decision).
     */
    @Test
    @DisplayName("re-running the seed leaves an existing, admin-disabled row disabled and does not fail")
    void reRunningTheSeedNeverReEnablesADisabledDomain() throws Exception {
        String sql = new String(new ClassPathResource(
                "db/migration/V217__trust_swiggy_instamart_amazonpay_sender_domains.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            jdbc.update("UPDATE gmail_trusted_sender_domains SET status = 'DISABLED' WHERE domain = 'swiggy.in'");

            jdbc.execute(sql);

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM gmail_trusted_sender_domains WHERE domain = 'swiggy.in'", String.class))
                    .isEqualTo("DISABLED");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM gmail_trusted_sender_domains WHERE domain = 'swiggy.in'", Integer.class))
                    .isEqualTo(1);
        } finally {
            jdbc.update("UPDATE gmail_trusted_sender_domains SET status = 'ACTIVE' WHERE domain = 'swiggy.in'");
        }
    }
}
