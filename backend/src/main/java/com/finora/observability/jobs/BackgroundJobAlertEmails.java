package com.finora.observability.jobs;

import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The text of the three background-job emails. Read on a phone, so short: what the job does, what
 * happened, since when (IST, as every admin email), and a link to the job on the page. An error is
 * shown only as its type and code location, never its message.
 */
final class BackgroundJobAlertEmails {

    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    /** Everything one email needs; {@code errorSummary} and the times may be null. */
    record Facts(String name, String purpose, JobState state, int failedItems, int handled, String errorSummary,
                 Instant brokenSince, Instant lastRunAt, Instant runningSince, Instant appStartedAt,
                 Instant lastSuccessAt, String link) {}

    private BackgroundJobAlertEmails() {}

    static String subject(AlertKind kind, String name) {
        return switch (kind) {
            case BROKEN -> "Background job needs attention: " + name;
            case STILL_BROKEN -> "Background job still needs attention: " + name;
            case WORKING_AGAIN -> "Background job working again: " + name;
        };
    }

    static String html(AlertKind kind, Facts f) {
        StringBuilder html = new StringBuilder();
        html.append("<p><strong>").append(escape(f.name())).append("</strong>: ").append(escape(f.purpose())).append("</p>");
        if (kind == AlertKind.WORKING_AGAIN) {
            html.append("<p>It is working again");
            if (f.lastSuccessAt() != null) html.append(": its last run finished normally at ").append(time(f.lastSuccessAt()));
            html.append(".</p>");
        } else {
            if (kind == AlertKind.STILL_BROKEN) html.append("<p>Still not fixed after 24 hours.</p>");
            html.append("<ul><li><strong>What happened:</strong> ").append(escape(whatHappened(f))).append("</li>");
            if (f.errorSummary() != null && f.state() != JobState.OVERDUE) {
                html.append("<li><strong>Error:</strong> ").append(escape(f.errorSummary())).append("</li>");
            }
            if (f.brokenSince() != null) {
                html.append("<li><strong>Broken since:</strong> ").append(time(f.brokenSince())).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><a href=\"").append(escape(f.link())).append("\">Open this job on the Background jobs page</a></p>");
        return html.toString();
    }

    static String whatHappened(Facts f) {
        return switch (f.state()) {
            case CRASHED -> "its last run crashed";
            case PARTLY_FAILED -> f.handled() > 0
                    ? f.failedItems() + " of " + f.handled() + " items failed in its last run"
                    : f.failedItems() + (f.failedItems() == 1 ? " item" : " items") + " failed in its last run";
            case OVERDUE -> {
                if (f.runningSince() != null) yield "it started at " + time(f.runningSince()) + " and has not finished";
                if (f.lastRunAt() != null) yield "it has not run since " + time(f.lastRunAt());
                yield "it has not run since the server started at " + time(f.appStartedAt());
            }
            case OK, OFF -> "it is fine";
        };
    }

    static String time(Instant at) {
        return at == null ? "an unknown time" : IST.format(at);
    }

    private static String escape(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }
}
