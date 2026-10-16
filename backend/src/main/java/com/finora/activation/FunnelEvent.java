package com.finora.activation;

/**
 * The three moments of the import funnel (Gate 2a spec §3), on the way to a first statement. The
 * fixed list: a client can send only these names, and V275's CHECK constraint holds the same three.
 */
public enum FunnelEvent {
    /** The import screen was shown: web {@code /app/import}, mobile Import tab gaining focus. */
    IMPORT_SCREEN_OPENED,
    /** A file was picked, before any upload starts -- including one the app then refuses itself. */
    FILE_CHOSEN,
    /** The review of staged rows was shown, from any path. */
    REVIEW_OPENED
}
