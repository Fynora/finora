package com.finora.imports.original;

import com.finora.exception.ApiException;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import com.finora.service.StatementStatusNotifier;
import com.finora.util.UserZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The one way staff see a customer's real statement: an Admin or Super Admin types a reason, the
 * held queue audits the opening and hands over the bytes, and a short-lived view row (one viewer,
 * {@link StatementOriginalView#LIFETIME}) is what page fetches are then checked against. Every page
 * is rendered from a fresh read through the queue, which refuses an item released since the view
 * opened, so a release ends every open view.
 *
 * <p>The reason is checked first, before the queue is asked: a refused reason writes no audit
 * entry and reads nothing. A view row is saved only once the bytes were read and (for a PDF) the
 * page count is known, so a failed opening tells nobody it happened -- though its audit entry
 * stays, written in its own transaction by the queue.
 *
 * <p>The customer's notice is written into the notification outbox in the same transaction as the
 * view row, so the two commit together: an opening that happened always reaches the customer, and
 * one that rolled back never does. The Super Admin email goes after commit, off the request (see
 * {@link OriginalViewNotices}).
 */
@Service
public class OriginalStatementViewService {

    static final int MIN_REASON = 15;
    static final int MAX_REASON = 500;

    private final AdminHeldImportService heldImports;
    private final HeldStatementService heldStatements;
    private final StatementOriginalViewRepository views;
    private final OriginalStatementRenderer renderer;
    private final ApplicationEventPublisher events;
    private final StatementStatusNotifier notifier;
    private final Clock clock;

    @Autowired
    public OriginalStatementViewService(AdminHeldImportService heldImports, HeldStatementService heldStatements,
                                        StatementOriginalViewRepository views, OriginalStatementRenderer renderer,
                                        ApplicationEventPublisher events, StatementStatusNotifier notifier) {
        this(heldImports, heldStatements, views, renderer, events, notifier, Clock.systemUTC());
    }

    OriginalStatementViewService(AdminHeldImportService heldImports, HeldStatementService heldStatements,
                                 StatementOriginalViewRepository views, OriginalStatementRenderer renderer,
                                 ApplicationEventPublisher events, StatementStatusNotifier notifier, Clock clock) {
        this.heldImports = heldImports;
        this.heldStatements = heldStatements;
        this.views = views;
        this.renderer = renderer;
        this.events = events;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** The day the customer is told: the opening's date in India, where the customer is. */
    public static LocalDate noticeDay(Instant openedAt) {
        return openedAt.atZone(UserZone.DEFAULT).toLocalDate();
    }

    /**
     * Cleaned, trimmed, then 15 to 500 characters, or a 400 naming the rule. The reason lands in an
     * audit entry and in the Super Admins' inboxes, so control characters other than line breaks
     * are removed, and so are bidirectional overrides and isolates, which make text display
     * differently from how it is stored -- the same cleaning a held import's resolve message gets.
     */
    public static String validReason(String raw) {
        String reason = raw == null ? "" : raw
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                .replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "")
                .strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Type a reason of 15 to 500 characters.");
        }
        return reason;
    }

    @Transactional
    public OriginalView openImport(UUID viewerId, UUID jobId, String rawReason) {
        String reason = validReason(rawReason);
        return open(viewerId, heldImports.contentForOriginalView(viewerId, jobId, reason), reason);
    }

    @Transactional
    public OriginalView openTrust(UUID viewerId, String heldId, String rawReason) {
        String reason = validReason(rawReason);
        return open(viewerId, heldStatements.contentForOriginalView(viewerId, heldId, reason), reason);
    }

    private OriginalView open(UUID viewerId, OriginalContent content, String reason) {
        int pages = "PDF".equals(content.format()) ? renderer.pageCount(content.content()) : 0;
        StatementOriginalView view = views.save(StatementOriginalView.open(content.jobId(),
                content.subjectUserId(), viewerId, content.queue(), content.itemRef(), content.format(),
                pages, clock.instant()));
        notifier.notifyOpenedForFix(view.getSubjectUserId(), view.getJobId(), noticeDay(view.getOpenedAt()));
        events.publishEvent(new OriginalViewOpened(view, reason));
        return new OriginalView(view.getId(), view.getFormat(), view.getPageCount(), view.getExpiresAt());
    }

    /** One page as a PNG. 404 for a view that is not the caller's or has expired (never a 403, so
     *  someone else's view id is not confirmed); 409 for a CSV view or a released item. */
    @Transactional(readOnly = true)
    public byte[] page(UUID viewerId, UUID viewId, int pageNumber) {
        StatementOriginalView view = live(viewerId, viewId);
        if (!"PDF".equals(view.getFormat())) {
            throw new ApiException(HttpStatus.CONFLICT, "This statement is a CSV file.");
        }
        return renderer.renderPage(fresh(view).content(), pageNumber);
    }

    /** A CSV view's lines, under the same rules as {@link #page}. */
    @Transactional(readOnly = true)
    public List<String> csv(UUID viewerId, UUID viewId) {
        StatementOriginalView view = live(viewerId, viewId);
        if (!"CSV".equals(view.getFormat())) {
            throw new ApiException(HttpStatus.CONFLICT, "This statement is a PDF file.");
        }
        return renderer.csvLines(fresh(view).content());
    }

    private StatementOriginalView live(UUID viewerId, UUID viewId) {
        return views.findById(viewId)
                .filter(v -> v.isLiveFor(viewerId, clock.instant()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This view has ended. Open the statement again."));
    }

    private OriginalContent fresh(StatementOriginalView view) {
        return "IMPORT".equals(view.getQueue())
                ? heldImports.contentForOpenView(view.getJobId())
                : heldStatements.contentForOpenView(view.getItemRef());
    }
}
