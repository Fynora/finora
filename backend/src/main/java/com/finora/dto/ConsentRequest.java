package com.finora.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/users/me/consent -- the consent step and the Settings "AI features" switch (V302).
 *
 * @param acceptTermsVersion the Terms/Privacy version the user just ticked the required box for, or
 *                           null when only the AI choice changes. Must equal the current version.
 * @param aiProcessing       the "AI features" answer, or null to leave it unchanged
 */
public record ConsentRequest(@Size(max = 32) String acceptTermsVersion, Boolean aiProcessing) {}
