package com.finora.observability.jobs.github;

/** Reading from GitHub failed. The message names what was read and the HTTP status, never a token. */
public class GitHubReadException extends RuntimeException {

    private final int status;

    public GitHubReadException(String what, int status) {
        super("GitHub " + what + " failed" + (status > 0 ? " with HTTP " + status : ""));
        this.status = status;
    }

    public int status() {
        return status;
    }
}
