package com.finora.notification.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class V195DeactivateStatementEmailTemplatesMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationTemplateRepository repository;

    @Test
    void theEmailRowsForBothStatementTypesAreNoLongerActive() {
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.EMAIL)).isEmpty();
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_HELD, NotificationChannel.EMAIL)).isEmpty();
    }

    @Test
    void thePushRowsForBothStatementTypesAreStillActive() {
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.PUSH)).isPresent();
        assertThat(repository.findByTypeAndChannelAndActiveTrue(
                NotificationType.IMPORT_STATEMENT_HELD, NotificationChannel.PUSH)).isPresent();
    }
}
