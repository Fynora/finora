package com.finora.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/** One row per (user, calendar day) the user made an authenticated request -- see
 *  V249__user_activity_days.sql. Not extending {@link BaseEntity}: writes go through
 *  {@code UserActivityDayRepository}'s native insert, never through JPA's save/merge path, so this
 *  entity only ever backs reads. No timestamp column on purpose: the privacy policy promises the
 *  date only, never the time. */
@Entity
@Table(name = "user_activity_days")
public class UserActivityDay {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public LocalDate getActivityDate() { return activityDate; }
}
