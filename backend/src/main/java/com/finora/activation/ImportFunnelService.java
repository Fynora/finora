package com.finora.activation;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Files one import-funnel moment under today's reporting-zone date (Gate 2a spec §3). */
@Service
public class ImportFunnelService {

    private final ImportFunnelDayRepository repository;
    private final ReportingDay reportingDay;

    public ImportFunnelService(ImportFunnelDayRepository repository, ReportingDay reportingDay) {
        this.repository = repository;
        this.reportingDay = reportingDay;
    }

    public void record(UUID userId, FunnelEvent event) {
        repository.record(userId, event.name(), reportingDay.today());
    }
}
