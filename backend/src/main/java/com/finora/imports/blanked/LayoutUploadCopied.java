package com.finora.imports.blanked;

import java.time.Instant;
import java.util.UUID;

/** A blanked copy was made of an upload of a layout flagged for review; published inside the result's transaction. */
public record LayoutUploadCopied(UUID actorId, String reference, String fingerprint, UUID jobId, String reason,
                                 Instant copiedAt) {}
