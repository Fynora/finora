package com.finora.imports.fix;

import java.util.UUID;

/** Where a blanked copy goes for a Claude fix: the private repository's incoming branch. */
public interface FixChannel {

    /**
     * Sends one attempt's blanked copy and its metadata, and returns the commit sha whose push
     * starts the fix workflow.
     *
     * @throws FixChannelException on any failed call; its message names the call and status only
     */
    String send(UUID jobId, int attempt, String trace, String metaJson);
}
