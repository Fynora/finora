package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * What a credit was, in the user's own words -- see V233 and the Plan 2 spec. A kind either counts
 * as income or does not; the five built-ins carry a fixed effect FlowClassifier reads by name.
 */
@Entity
@Table(name = "inflow_kinds")
public class InflowKind {

    public enum BuiltIn {
        INCOME("Income", true),
        FAMILY_SUPPORT("Family support", true),
        OWN_MONEY("My own money", false),
        PAID_BACK("Paid back to me", false),
        REFUND("Refund", false);

        private final String defaultName;
        private final boolean countsAsIncome;

        BuiltIn(String defaultName, boolean countsAsIncome) {
            this.defaultName = defaultName;
            this.countsAsIncome = countsAsIncome;
        }

        public String defaultName() { return defaultName; }
        public boolean countsAsIncome() { return countsAsIncome; }
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "counts_as_income", nullable = false)
    private boolean countsAsIncome;

    @Enumerated(EnumType.STRING)
    @Column(name = "built_in")
    private BuiltIn builtIn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isCountsAsIncome() { return countsAsIncome; }
    public void setCountsAsIncome(boolean countsAsIncome) { this.countsAsIncome = countsAsIncome; }
    public BuiltIn getBuiltIn() { return builtIn; }
    public void setBuiltIn(BuiltIn builtIn) { this.builtIn = builtIn; }
    public Instant getCreatedAt() { return createdAt; }
}
