package com.finora.service;

import com.finora.entity.SuspensionReason;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** suspension.json in the data download (privacy workstream 10): the suspension is data about the customer. */
class DataExportSuspensionTest {

    @Test
    void aSuspendedAccount_exportsWhenWhyAndTheMessage() {
        User user = new User();
        Instant since = Instant.parse("2026-11-01T10:00:00Z");
        user.recordSuspension(SuspensionReason.SECURITY_RISK, "Synthetic message.", since);

        var rows = DataExportService.suspensionOf(user);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.suspendedAt()).isEqualTo(since);
            assertThat(r.reason()).isEqualTo("SECURITY_RISK");
            assertThat(r.reasonText()).isEqualTo(SuspensionReason.SECURITY_RISK.customerText());
            assertThat(r.message()).isEqualTo("Synthetic message.");
        });
    }

    /** Suspended before reasons were recorded: the row is still there, with no reason. */
    @Test
    void aSuspensionWithNoRecordedReason_exportsWithNullReason() {
        User user = new User();
        user.setStatus(User.STATUS_SUSPENDED);
        ReflectionTestUtils.setField(user, "suspendedAt", Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(DataExportService.suspensionOf(user)).singleElement().satisfies(r -> {
            assertThat(r.reason()).isNull();
            assertThat(r.reasonText()).isNull();
        });
    }

    @Test
    void anActiveAccount_exportsNothing() {
        assertThat(DataExportService.suspensionOf(new User())).isEmpty();
    }
}
