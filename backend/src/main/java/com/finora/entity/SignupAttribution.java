package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Where a self-service sign-up came from (V286): the host of the page that linked to Fynora and
 * the campaign tags on the landing link; and (V300) the first public page of the visit, as a path.
 * One row per account, written by
 * {@link com.finora.service.SignupAttributionService} in the registration transaction, and only
 * when the browser reported anything at all -- see the migration's own comment.
 */
@Entity
@Table(name = "signup_attributions")
public class SignupAttribution {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "referrer_host")
    private String referrerHost;

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "utm_medium")
    private String utmMedium;

    @Column(name = "utm_campaign")
    private String utmCampaign;

    @Column(name = "utm_content")
    private String utmContent;

    @Column(name = "landing_path")
    private String landingPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SignupAttribution() {}

    public SignupAttribution(UUID userId, String referrerHost, String utmSource, String utmMedium,
                             String utmCampaign, String utmContent, String landingPath) {
        this.userId = userId;
        this.referrerHost = referrerHost;
        this.utmSource = utmSource;
        this.utmMedium = utmMedium;
        this.utmCampaign = utmCampaign;
        this.utmContent = utmContent;
        this.landingPath = landingPath;
    }

    /** Without a landing path -- a row that predates V300, or one whose visit began off the public pages. */
    public SignupAttribution(UUID userId, String referrerHost, String utmSource, String utmMedium,
                             String utmCampaign, String utmContent) {
        this(userId, referrerHost, utmSource, utmMedium, utmCampaign, utmContent, null);
    }

    public UUID getUserId() { return userId; }
    public String getReferrerHost() { return referrerHost; }
    public String getUtmSource() { return utmSource; }
    public String getUtmMedium() { return utmMedium; }
    public String getUtmCampaign() { return utmCampaign; }
    public String getUtmContent() { return utmContent; }
    public String getLandingPath() { return landingPath; }
    public Instant getCreatedAt() { return createdAt; }
}
