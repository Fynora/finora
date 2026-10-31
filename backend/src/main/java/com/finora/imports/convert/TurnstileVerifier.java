package com.finora.imports.convert;

/**
 * Whether a Cloudflare Turnstile token from the converter page is real and unused. The converter's
 * main defence against scripts: every conversion needs a fresh token, and a token is single-use.
 */
public interface TurnstileVerifier {

    /** True only when Cloudflare says the token is valid. Any doubt (no secret configured, a network
     *  failure, an unexpected reply) is false: the converter fails closed. */
    boolean verify(String token);
}
