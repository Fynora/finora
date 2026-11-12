package com.finora.imports.blanked;

import com.finora.service.HeldItemAdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails the Super Admins that a blanked copy was made of a flagged layout's upload. After the
 * result commits, so a copy whose result rolled back is never announced; off the request, on the
 * executor the held-statement opening uses, for the same reason: the email provider can take
 * seconds per recipient. Never throws.
 */
@Component
public class LayoutUploadCopyNotices {

    private static final Logger log = LoggerFactory.getLogger(LayoutUploadCopyNotices.class);

    private final HeldItemAdminAlertService alerts;

    public LayoutUploadCopyNotices(HeldItemAdminAlertService alerts) {
        this.alerts = alerts;
    }

    @Async("originalViewAlertExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCopied(LayoutUploadCopied event) {
        try {
            alerts.alertLayoutUploadCopied(event);
        } catch (RuntimeException e) {
            log.warn("Super Admin alert for the blanked copy of upload {} failed", event.reference(), e);
        }
    }
}
