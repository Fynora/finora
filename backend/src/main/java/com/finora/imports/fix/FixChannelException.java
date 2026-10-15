package com.finora.imports.fix;

/** A GitHub call that failed. The message names the call and the HTTP status, never a body: a
 *  body could echo what was sent. Status 0 means no response arrived. */
public class FixChannelException extends RuntimeException {

    private final int status;

    public FixChannelException(String what, int status) {
        super(what + " failed with status " + status);
        this.status = status;
    }

    public int status() { return status; }
}
