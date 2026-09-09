package com.finora.integrations.google;

/**
 * Which client started a Gmail OAuth flow, so the callback (GoogleOAuthController#callback) knows
 * which of two fixed, configured URLs to send the browser back to -- see
 * GoogleOAuthProperties#postConnectRedirect / #postConnectRedirectMobile.
 *
 * <p>A closed set, not an arbitrary client-supplied URL, deliberately: GoogleOAuthProperties'
 * postConnectRedirect has always been "configuration, never a request parameter" specifically so
 * the unauthenticated callback can never become an open redirect. This enum preserves that --
 * every value maps to one of two developer-configured targets, never to anything read out of a
 * request.
 */
public enum ReturnPlatform {
    WEB, MOBILE;

    /**
     * Parses the {@code platform} request parameter on the (authenticated) {@code /connect}
     * call. Anything other than exactly {@code "MOBILE"} -- including a missing param, which is
     * every call the web frontend has ever made -- defaults to {@link #WEB}, so existing behavior
     * is unchanged for a caller that has no reason to know this parameter exists.
     */
    public static ReturnPlatform fromRequestParam(String value) {
        return "MOBILE".equalsIgnoreCase(value) ? MOBILE : WEB;
    }

    /**
     * Parses a value read back from {@link GmailOAuthState#getReturnPath()}. Same WEB default for
     * anything unrecognized, including null -- a state row from before this field was wired up,
     * or a value some future migration doesn't recognize, is safer resolved to the redirect that
     * has always been correct than to fail the callback outright.
     */
    static ReturnPlatform fromStored(String value) {
        return MOBILE.name().equals(value) ? MOBILE : WEB;
    }
}
