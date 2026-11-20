package com.finora.integrations.google;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The one-time ticket Google's redirect handed the browser, posted back by the signed-in app to
 * finish a Gmail connection. See {@link GmailConnectionService#confirmConnect}.
 */
public record GmailConnectConfirmRequest(@NotBlank @Size(max = 128) String ticket) {}
