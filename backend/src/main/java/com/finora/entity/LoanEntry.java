package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One line of a person's balance (V281). See PeopleLedger for how the directions add up. */
@Entity
@Table(name = "loan_entries")
public class LoanEntry {

    /** LENT and REPAID name a debit, PAID_BACK and BORROWED a credit; CASH_SETTLED names no row;
     *  LET_GO names the LENT debit it turns back into spending, or no row. */
    public enum Direction { LENT, PAID_BACK, BORROWED, REPAID, CASH_SETTLED, LET_GO }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "person_key", nullable = false)
    private String personKey;

    @Column(name = "txn_id")
    private UUID txnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoanEntry() {}

    public LoanEntry(UUID userId, String personKey, UUID txnId, Direction direction, BigDecimal amount) {
        this.userId = userId;
        this.personKey = personKey;
        this.txnId = txnId;
        this.direction = direction;
        this.amount = amount;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getPersonKey() { return personKey; }
    public UUID getTxnId() { return txnId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
}
