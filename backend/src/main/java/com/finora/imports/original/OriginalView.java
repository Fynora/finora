package com.finora.imports.original;

import java.time.Instant;
import java.util.UUID;

/** An opened view, as the admin portal is told about it: what to fetch and until when. */
public record OriginalView(UUID viewId, String format, int pageCount, Instant expiresAt) {}
