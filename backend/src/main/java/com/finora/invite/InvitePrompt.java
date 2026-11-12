package com.finora.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code invite_prompts} row (V315): whether, and when, this person was shown the invite-a-friend
 * card, and what they tapped. Written only through {@link InvitePromptRepository}'s upserts; this
 * entity is for reading (eligibility, the data export).
 */
@Entity
@Table(name = "invite_prompts")
public class InvitePrompt {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "times_shown", nullable = false)
    private short timesShown;

    @Column(name = "last_shown_on")
    private LocalDate lastShownOn;

    @Column(name = "shared_on")
    private LocalDate sharedOn;

    @Column(name = "dismissed_on")
    private LocalDate dismissedOn;

    protected InvitePrompt() {}

    public UUID getUserId() { return userId; }
    public int getTimesShown() { return timesShown; }
    public LocalDate getLastShownOn() { return lastShownOn; }
    public LocalDate getSharedOn() { return sharedOn; }
    public LocalDate getDismissedOn() { return dismissedOn; }
}
