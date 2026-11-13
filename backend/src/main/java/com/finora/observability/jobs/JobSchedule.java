package com.finora.observability.jobs;

import java.time.Duration;

/**
 * When a job is meant to run: exactly one of {@code interval} (fixed delay or rate, with its
 * {@code initialDelay}) or {@code cron} is set. {@code description} is the schedule in words.
 */
public record JobSchedule(String key, Duration interval, Duration initialDelay, String cron, String description) {}
