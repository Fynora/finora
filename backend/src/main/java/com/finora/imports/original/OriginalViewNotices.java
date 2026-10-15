package com.finora.imports.original;

import com.finora.service.HeldItemAdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails the Super Admins that a held statement's real pages were opened. The customer's notice is
 * not sent from here: it is written into the outbox inside the opening's own transaction, by
 * {@link OriginalStatementViewService}.
 */
@Component
public class OriginalViewNotices {

    private static final Logger log = LoggerFactory.getLogger(OriginalViewNotices.class);

    private final HeldItemAdminAlertService alerts;

    public OriginalViewNotices(HeldItemAdminAlertService alerts) {
        this.alerts = alerts;
    }

    /**
     * After the opening commits, so an opening that rolled back is never announced; and off the
     * request, on {@code originalViewAlertExecutor}. The opening runs under one of the import slots
     * customers' uploads share, and the email provider can take seconds per recipient -- measured
     * 2026-10-15: with a 3-second provider the opening took 3.3 s while it sent here on the request.
     * Never throws.
     */
    @Async("originalViewAlertExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(OriginalViewOpened event) {
        try {
            alerts.alertOriginalOpened(event.view().getId(), event.reason());
        } catch (RuntimeException e) {
            log.warn("Super Admin alert for original view {} failed", event.view().getId(), e);
        }
    }
}
