package com.finora.activation;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code account_deletion_reasons} row (V278). Deliberately holds nothing that links it to the
 * person who deleted their account: no user id, and the month rather than the day.
 */
@Entity
@Table(name = "account_deletion_reasons")
public class AccountDeletionReason {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private DeletionReason reason;

    /** Always the first day of the month, in the reporting zone. */
    @Column(name = "deletion_month", nullable = false)
    private LocalDate deletionMonth;

    @Column(name = "had_data", nullable = false)
    private boolean hadData;

    protected AccountDeletionReason() {}

    public AccountDeletionReason(DeletionReason reason, LocalDate deletionMonth, boolean hadData) {
        this.reason = reason;
        this.deletionMonth = deletionMonth;
        this.hadData = hadData;
    }

    public UUID getId() { return id; }
    public DeletionReason getReason() { return reason; }
    public LocalDate getDeletionMonth() { return deletionMonth; }
    public boolean isHadData() { return hadData; }
}
