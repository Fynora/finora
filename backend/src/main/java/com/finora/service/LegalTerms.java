package com.finora.service;

/**
 * The version of the Terms of Service and Privacy Policy a new account accepts, recorded on
 * {@code User.termsVersion} (V223).
 *
 * <p>This is the "Last updated" month shown on frontend/src/pages/Terms.tsx and Privacy.tsx. When
 * either page's terms change in substance, bump this in the same change as that date, so accounts
 * created afterwards record the new version and older accounts keep the one they actually saw.
 */
public final class LegalTerms {

    public static final String CURRENT_VERSION = "2026-09";

    private LegalTerms() {
    }
}
