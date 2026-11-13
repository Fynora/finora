package com.finora.observability.jobs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@code @Scheduled} method that only hands its work to another thread and returns. The hand-off
 * itself is not recorded (it always "succeeds" in a millisecond); the method wraps the work in
 * {@link BackgroundJobRunner#recorded} instead, so the run's real outcome is what the Background jobs
 * page shows.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RecordedWhereItRuns {}
