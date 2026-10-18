package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Per-person choices (V279). family: never asked "lent?" or "settle it" for this person. */
@Entity
@Table(name = "person_settings")
public class PersonSetting {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "person_key", nullable = false)
    private String personKey;

    @Column(name = "family", nullable = false)
    private boolean family;

    protected PersonSetting() {}

    public PersonSetting(UUID userId, String personKey, boolean family) {
        this.userId = userId;
        this.personKey = personKey;
        this.family = family;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getPersonKey() { return personKey; }
    public boolean isFamily() { return family; }
    public void setFamily(boolean family) { this.family = family; }
}
