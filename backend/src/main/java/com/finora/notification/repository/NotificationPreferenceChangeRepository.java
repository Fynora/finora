package com.finora.notification.repository;

import com.finora.notification.domain.NotificationPreferenceChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** V306. Append-only: nothing here updates or deletes, and the account purge leaves these rows. */
public interface NotificationPreferenceChangeRepository extends JpaRepository<NotificationPreferenceChange, UUID> {

    List<NotificationPreferenceChange> findByUserIdOrderByRecordedAtAsc(UUID userId);
}
