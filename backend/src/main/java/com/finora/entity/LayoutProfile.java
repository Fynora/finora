package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An operator-named family of statement layouts -- "Kotak Credit Card" -- whose members are
 * {@link RegisteredLayout} rows, each at its own version number (V243).
 *
 * <p>A bank changing its statement format produces a new layout fingerprint, which the registry
 * has always recorded as an unrelated row. A profile is what says "this is the next version of a
 * layout we already know". Membership is only ever set by an operator: the engine has no reliable
 * way to tell a bank's new format from a different bank's lookalike, and a wrong guess here would
 * read as a considered decision.
 */
@Entity
@Table(name = "layout_profile")
public class LayoutProfile {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String name;

    /** The automatic grouping key this profile answers to (V244), e.g. "KOTAK|CREDIT_CARD"; null
     *  for a profile only an operator uses. Written only by LayoutProfileAutoLinker. */
    @Column(name = "auto_key", length = 96, insertable = false, updatable = false)
    private String autoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected LayoutProfile() {}

    public LayoutProfile(String name) {
        rename(name);
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A profile needs a name");
        this.name = name.trim();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getAutoKey() { return autoKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
