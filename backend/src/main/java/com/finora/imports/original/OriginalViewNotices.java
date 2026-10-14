package com.finora.imports.original;

import com.finora.service.HeldItemAdminAlertService;
import com.finora.service.StatementStatusNotifier;
import com.finora.util.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells people a held statement's real pages were opened: the Super Admins by email, the customer
 * by push and email. Never throws; a failed send is logged and the other still goes.
 */
@Component
public class OriginalViewNotices {

    private static final Logger log = LoggerFactory.getLogger(OriginalViewNotices.class);

    private final HeldItemAdminAlertService alerts;
    private final StatementStatusNotifier notifier;
    private final TransactionTemplate ownTransaction;

    public OriginalViewNotices(HeldItemAdminAlertService alerts, StatementStatusNotifier notifier,
                               PlatformTransactionManager transactionManager) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * After the opening commits: an opening that rolled back never tells anyone it happened.
     *
     * <p>The customer notice is written in a transaction of its own. At this phase the opening's
     * transaction has committed but is still bound to the thread, and the notification outbox joins
     * whatever transaction it finds ({@code NotificationService.request} is deliberately not
     * {@code @Transactional}), so without one the outbox rows would join a transaction that never
     * commits again, and be lost. The Super Admin email writes nothing, so it needs no transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(OriginalViewOpened event) {
        StatementOriginalView view = event.view();
        try {
            alerts.alertOriginalOpened(view.getId());
        } catch (RuntimeException e) {
            log.warn("Super Admin alert for original view {} failed", view.getId(), e);
        }
        try {
            ownTransaction.executeWithoutResult(status -> notifier.notifyOpenedForFix(view.getSubjectUserId(),
                    view.getJobId(), view.getOpenedAt().atZone(UserZone.DEFAULT).toLocalDate()));
        } catch (RuntimeException e) {
            log.warn("Customer notice for original view {} failed", view.getId(), e);
        }
    }
}
