package com.finora.imports.fix;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the automatic Claude fix did for one held statement, for the admin pages and the 48-hour
 * email: a state, a reason, the attempt and PR numbers. Never statement text.
 *
 * @param state      a {@link ClaudeFixJob.State} name, or {@code OFF} when the switch is off and no
 *                   decision was ever recorded
 * @param reason     a {@link ClaudeFixJob.Reason} name, or null
 * @param monthlyCap the configured monthly cap, for the "this month's runs are used" wording
 */
@Schema(name = "HeldClaudeFix")
public record ClaudeFixStatus(String state, String reason, Integer publicPr, Integer privatePr, int attempt,
                              int monthlyCap) {

    public static final String OFF = "OFF";

    public static ClaudeFixStatus of(ClaudeFixJob job, int monthlyCap) {
        return new ClaudeFixStatus(job.getState().name(), job.getReason() == null ? null : job.getReason().name(),
                job.getPublicPr(), job.getPrivatePr(), job.getAttempt(), monthlyCap);
    }

    public static ClaudeFixStatus off(int monthlyCap) {
        return new ClaudeFixStatus(OFF, null, null, null, 0, monthlyCap);
    }

    /** The overdue email's one line, after "Claude: ". Plain text; the caller escapes it and adds
     *  the PR link. */
    public String emailLine() {
        ClaudeFixJob.Reason why = reason == null ? null : ClaudeFixJob.Reason.valueOf(reason);
        if (OFF.equals(state)) return "off";
        return switch (ClaudeFixJob.State.valueOf(state)) {
            case NOT_STARTED -> switch (why) {
                case OFF -> "off";
                case DIFFERS -> "not tried (the blanked copy does not match)";
                case NOT_CHECKED -> "not tried (the blanked copy was not checked)";
                case REFUSED -> "not tried (the blanked copy was refused)";
                case CSV -> "not tried (the statement is a CSV)";
                case CAP_REACHED -> "not tried (monthly limit reached)";
                case null, default -> "could not start";
            };
            case SENT -> "working (attempt " + attempt + " of " + ClaudeFixJob.MAX_ATTEMPTS + ")";
            case PR_OPEN -> "PR #" + publicPr + " opened";
            case REREADING -> "fix deployed, re-reading";
            case FIXED -> "fixed by PR #" + publicPr;
            case GAVE_UP -> switch (why) {
                case STILL_HELD -> "fix deployed, statement still held";
                case DECIDED -> "stopped, a person decided this hold";
                case null, default -> "gave up after " + attempt + (attempt == 1 ? " attempt" : " attempts");
            };
        };
    }

    /** The Finora PR this status links to in the email, or null. */
    public Integer linkedPublicPr() {
        return switch (state) {
            case "PR_OPEN", "FIXED" -> publicPr;
            default -> null;
        };
    }
}
