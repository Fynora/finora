package com.finora.activation;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

/** One {@code import_funnel_days} row (V275): a user reached {@link #event} on {@link #funnelDate}. */
@Entity
@Table(name = "import_funnel_days")
public class ImportFunnelDay {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 32)
    private FunnelEvent event;

    @Column(name = "funnel_date", nullable = false)
    private LocalDate funnelDate;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public FunnelEvent getEvent() { return event; }
    public LocalDate getFunnelDate() { return funnelDate; }
}
