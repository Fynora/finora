package com.finora.imports.original;

import java.util.UUID;

/**
 * A held statement's real bytes, as a held queue hands them over for an Admin's in-browser view.
 * {@code queue} is {@code "IMPORT"} or {@code "TRUST"}; {@code itemRef} is the job id as text, or
 * the Held ID. {@code format} is the stored source format, upper case.
 */
public record OriginalContent(UUID jobId, UUID subjectUserId, String queue, String itemRef, String format,
                              byte[] content) {

    /** How a refused unlock ends for a view, as "...so no blanked copy can be made" ends for a copy. */
    public static final String CANNOT_BE_SHOWN = "it cannot be shown";

    /** Ids and a length only: the record's default would print the statement's bytes. */
    @Override
    public String toString() {
        return "OriginalContent[jobId=" + jobId + ", subjectUserId=" + subjectUserId + ", queue=" + queue
                + ", itemRef=" + itemRef + ", format=" + format + ", content=" + content.length + " bytes]";
    }
}
