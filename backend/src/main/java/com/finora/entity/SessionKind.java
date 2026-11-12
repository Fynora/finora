package com.finora.entity;

/**
 * Which session limits a refresh-token session lives by. Decided once, at sign-in, and copied by
 * every rotation -- never re-read from a later request (see RefreshTokenService#issue(UUID)).
 *
 * <p>{@code MOBILE} is only for a sign-in from a phone-app build that requires a PIN to open the
 * app, so a long session is always guarded on the device. Everything else, including older app
 * builds and anything that did not say, is {@code WEB}.
 */
public enum SessionKind {
    WEB,
    MOBILE
}
