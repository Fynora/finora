package com.finora.imports.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.blanked.BlankedCopy;
import com.finora.imports.blanked.BlankedCopyOutcome;
import com.finora.imports.blanked.BlankedCopyRefusedException;
import com.finora.imports.blanked.BlankedCopyService;
import com.finora.imports.blanked.StaffStatementContent;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldItemAdminAlertService;
import com.finora.service.HeldStatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FixJobStarterTest {

    /** 2026-10-31 20:00 UTC is 1 November 01:30 in India: the cap's month is November. */
    private static final Instant NOW = Instant.parse("2026-10-31T20:00:00Z");
    private static final String TRACE = "# synthetic trace\n";

    private ClaudeFixJobRepository jobs;
    private ImportJobRepository importJobs;
    private HeldStatementRepository heldStatements;
    private ImportVerificationFindingRepository findings;
    private BlankedCopyService copies;
    private FixChannel channel;
    private AdminHeldImportService importQueue;
    private HeldStatementService trustQueue;
    private HeldItemAdminAlertService alerts;
    private ClaudeFixProperties properties;
    private FixJobStarter starter;
    /** The last version saved of each row, in the order rows were first saved. */
    private final Map<UUID, ClaudeFixJob> rows = new LinkedHashMap<>();
    private final List<ClaudeFixJob> saved = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobs = mock(ClaudeFixJobRepository.class);
        importJobs = mock(ImportJobRepository.class);
        heldStatements = mock(HeldStatementRepository.class);
        findings = mock(ImportVerificationFindingRepository.class);
        copies = mock(BlankedCopyService.class);
        channel = mock(FixChannel.class);
        importQueue = mock(AdminHeldImportService.class);
        trustQueue = mock(HeldStatementService.class);
        alerts = mock(HeldItemAdminAlertService.class);
        properties = new ClaudeFixProperties();
        properties.setEnabled(true);
        ObjectProvider<AdminHeldImportService> importProvider = mock(ObjectProvider.class);
        when(importProvider.getObject()).thenReturn(importQueue);
        ObjectProvider<HeldStatementService> trustProvider = mock(ObjectProvider.class);
        when(trustProvider.getObject()).thenReturn(trustQueue);
        org.mockito.stubbing.Answer<ClaudeFixJob> store = inv -> {
            ClaudeFixJob row = inv.getArgument(0);
            rows.put(row.getJobId(), row);
            saved.clear();
            saved.addAll(rows.values());
            return row;
        };
        when(jobs.save(any(ClaudeFixJob.class))).thenAnswer(store);
        when(jobs.saveAndFlush(any(ClaudeFixJob.class))).thenAnswer(store);
        starter = new FixJobStarter(jobs, importJobs, heldStatements, findings, copies, channel, importProvider,
                trustProvider, alerts, properties, new ObjectMapper(), Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ImportJob importHold(String format) {
        ImportJob job = mock(ImportJob.class);
        UUID id = UUID.randomUUID();
        when(job.getId()).thenReturn(id);
        when(job.getSourceFormat()).thenReturn(format);
        when(job.getStatus()).thenReturn(ImportJob.Status.HELD_FOR_REVIEW);
        when(job.getFailureCode()).thenReturn("IllegalStateException");
        when(importJobs.findById(id)).thenReturn(Optional.of(job));
        when(importQueue.contentForSystemBlankedCopy(id))
                .thenReturn(new StaffStatementContent(id.toString(), format, new byte[] {1}));
        return job;
    }

    private void copyChecks(BlankedCopy.Check check) {
        when(copies.make(any())).thenReturn(new BlankedCopy("blanked.trace", TRACE, check, null, List.of(), List.of()));
    }

    private ClaudeFixJob onlySaved() {
        assertThat(saved).hasSize(1);
        return saved.get(0);
    }

    @Test
    void switchOffRecordsOffAndSendsNothing() {
        properties.setEnabled(false);
        ImportJob job = importHold("PDF");

        starter.onHeld(job.getId());

        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.OFF);
        verifyNoInteractions(copies, channel, importQueue);
    }

    @Test
    void aCsvHoldRecordsCsv() {
        ImportJob job = importHold("CSV");

        starter.onHeld(job.getId());

        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.CSV);
        verifyNoInteractions(copies, channel);
    }

    @Test
    void aDiffersCopyRecordsDiffersAndSendsNothing() {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.DIFFERS);

        starter.onHeld(job.getId());

        assertThat(onlySaved().getState()).isEqualTo(ClaudeFixJob.State.NOT_STARTED);
        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.DIFFERS);
        verifyNoInteractions(channel);
        UUID id = job.getId();
        verify(importQueue).recordBlankedCopyResult(eq(null), eq(id), any(BlankedCopyOutcome.class));
    }

    @Test
    void aNotCheckedCopyRecordsNotChecked() {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.NOT_CHECKED);

        starter.onHeld(job.getId());

        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.NOT_CHECKED);
        verifyNoInteractions(channel);
    }

    @Test
    void aRefusedCopyRecordsRefused() {
        ImportJob job = importHold("PDF");
        when(copies.make(any())).thenThrow(new BlankedCopyRefusedException(List.of("email address")));

        starter.onHeld(job.getId());

        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.REFUSED);
        verifyNoInteractions(channel);
        UUID id = job.getId();
        ArgumentCaptor<BlankedCopyOutcome> outcome = ArgumentCaptor.forClass(BlankedCopyOutcome.class);
        verify(importQueue).recordBlankedCopyResult(eq(null), eq(id), outcome.capture());
        assertThat(outcome.getValue().check()).isEqualTo(BlankedCopyOutcome.REFUSED);
    }

    @Test
    void aMatchesCopySendsAttemptOneAndRecordsSent() {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.MATCHES);
        UUID id = job.getId();
        when(channel.send(eq(id), eq(1), eq(TRACE), anyString())).thenReturn("sha-1");

        starter.onHeld(job.getId());

        ClaudeFixJob row = onlySaved();
        assertThat(row.getState()).isEqualTo(ClaudeFixJob.State.SENT);
        assertThat(row.getAttempt()).isEqualTo(1);
        assertThat(row.getSentCommitSha()).isEqualTo("sha-1");
        assertThat(row.getSentAt()).isEqualTo(NOW);
    }

    @Test
    void aTrustHoldIsCopiedThroughTheTrustQueueWithItsBankAndFindings() throws Exception {
        ImportJob job = mock(ImportJob.class);
        UUID id = UUID.randomUUID();
        when(job.getId()).thenReturn(id);
        when(job.getSourceFormat()).thenReturn("PDF");
        when(job.getStatus()).thenReturn(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        when(importJobs.findById(id)).thenReturn(Optional.of(job));
        HeldStatement held = mock(HeldStatement.class);
        when(held.getHeldId()).thenReturn("HS-1");
        when(held.getBankName()).thenReturn("Synthetic Bank");
        when(held.getHoldReasonCategories()).thenReturn(List.of("BALANCE_CHAIN"));
        when(heldStatements.findByImportJobId(id)).thenReturn(Optional.of(held));
        when(trustQueue.contentForSystemBlankedCopy("HS-1"))
                .thenReturn(new StaffStatementContent(id.toString(), "PDF", new byte[] {1}));
        ImportVerificationFinding failed = mock(ImportVerificationFinding.class);
        when(failed.getRule()).thenReturn("BALANCE_CHAIN");
        when(failed.getOutcome()).thenReturn("FAILED");
        ImportVerificationFinding passed = mock(ImportVerificationFinding.class);
        when(passed.getRule()).thenReturn("ROW_ACCOUNTING");
        when(passed.getOutcome()).thenReturn("PASS");
        when(findings.findByImportJobIdOrderBySectionIndexAscRuleAsc(id)).thenReturn(List.of(failed, passed));
        copyChecks(BlankedCopy.Check.MATCHES);
        when(channel.send(eq(id), eq(1), eq(TRACE), anyString())).thenReturn("sha-t");

        starter.onHeld(id);

        assertThat(onlySaved().getState()).isEqualTo(ClaudeFixJob.State.SENT);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(channel).send(eq(id), eq(1), eq(TRACE), meta.capture());
        JsonNode json = new ObjectMapper().readTree(meta.getValue());
        assertThat(json.get("bank").asText()).isEqualTo("Synthetic Bank");
        assertThat(json.get("reasonCodes").toString()).isEqualTo("[\"BALANCE_CHAIN\"]");
        assertThat(json.get("findingKinds").toString()).isEqualTo("[\"BALANCE_CHAIN:FAILED\"]");
        verify(trustQueue).recordBlankedCopyResult(eq(null), eq("HS-1"), any(BlankedCopyOutcome.class));
    }

    @Test
    void theMetaJsonCarriesOnlyTheAllowedFields() throws Exception {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.MATCHES);
        when(channel.send(any(), anyInt(), anyString(), anyString())).thenReturn("sha-1");

        starter.onHeld(job.getId());

        UUID id = job.getId();
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(channel).send(eq(id), eq(1), eq(TRACE), meta.capture());
        JsonNode json = new ObjectMapper().readTree(meta.getValue());
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("reference", "attempt", "bank", "reasonCodes", "findingKinds");
        assertThat(json.get("reference").asText()).isEqualTo(id.toString());
        assertThat(json.get("attempt").asInt()).isEqualTo(1);
        assertThat(json.get("bank").isNull()).isTrue();
        assertThat(json.get("reasonCodes").toString()).isEqualTo("[\"IllegalStateException\"]");
        assertThat(json.get("findingKinds").toString()).isEqualTo("[]");
    }

    @Test
    void theCapStopsTheFifty_firstAndEmailsOnce() {
        Instant monthStart = Instant.parse("2026-10-31T18:30:00Z"); // 1 November 00:00 India time
        Instant monthEnd = Instant.parse("2026-11-30T18:30:00Z");
        when(jobs.countStartedBetween(monthStart, monthEnd)).thenReturn(50L);
        when(jobs.claimCapAlert("2026-11", NOW)).thenReturn(1, 0);
        ImportJob first = importHold("PDF");
        ImportJob second = importHold("PDF");

        starter.onHeld(first.getId());
        starter.onHeld(second.getId());

        assertThat(saved).extracting(ClaudeFixJob::getReason)
                .containsExactly(ClaudeFixJob.Reason.CAP_REACHED, ClaudeFixJob.Reason.CAP_REACHED);
        verify(alerts, times(1)).alertClaudeFixCapReached(YearMonth.of(2026, 11), 50);
        verifyNoInteractions(copies, channel);
    }

    @Test
    void theFortyNinthStillStarts() {
        when(jobs.countStartedBetween(any(), any())).thenReturn(49L);
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.MATCHES);
        when(channel.send(any(), anyInt(), anyString(), anyString())).thenReturn("sha-49");

        starter.onHeld(job.getId());

        assertThat(onlySaved().getState()).isEqualTo(ClaudeFixJob.State.SENT);
    }

    @Test
    void aSecondHoldOfTheSameJobStartsNothing() {
        ImportJob job = importHold("PDF");
        when(jobs.existsById(job.getId())).thenReturn(true);

        starter.onHeld(job.getId());

        assertThat(saved).isEmpty();
        verifyNoInteractions(copies, channel, importQueue);
    }

    @Test
    void aJobNoLongerHeldStartsNothing() {
        ImportJob job = importHold("PDF");
        when(job.getStatus()).thenReturn(ImportJob.Status.QUEUED);

        starter.onHeld(job.getId());

        assertThat(saved).isEmpty();
        verifyNoInteractions(copies, channel);
    }

    @Test
    void anyExceptionRecordsErrorAndNeverThrows() {
        ImportJob job = importHold("PDF");
        when(copies.make(any())).thenThrow(new IllegalStateException("boom"));

        starter.onHeld(job.getId());

        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.ERROR);
        verify(channel, never()).send(any(), anyInt(), anyString(), anyString());
    }

    @Test
    void aFailedSendRecordsError() {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.MATCHES);
        when(channel.send(any(), anyInt(), anyString(), anyString()))
                .thenThrow(new FixChannelException("put trace", 500));

        starter.onHeld(job.getId());

        assertThat(onlySaved().getState()).isEqualTo(ClaudeFixJob.State.NOT_STARTED);
        assertThat(onlySaved().getReason()).isEqualTo(ClaudeFixJob.Reason.ERROR);
    }

    @Test
    void theRowIsReservedBeforeTheCopyLeaves() {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.MATCHES);
        UUID id = job.getId();
        ClaudeFixJob[] atSend = {null};
        when(channel.send(eq(id), eq(1), eq(TRACE), anyString())).thenAnswer(inv -> {
            atSend[0] = rows.get(id);
            return "sha-1";
        });

        starter.onHeld(id);

        assertThat(atSend[0]).as("a row existed when the copy was sent").isNotNull();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(jobs, channel);
        order.verify(jobs).saveAndFlush(any(ClaudeFixJob.class));
        order.verify(channel).send(eq(id), eq(1), eq(TRACE), anyString());
        assertThat(onlySaved().getState()).isEqualTo(ClaudeFixJob.State.SENT);
        assertThat(onlySaved().getReason()).isNull();
    }

    @Test
    void anotherStartHoldingTheRowMeansThisOneSendsNothing() {
        ImportJob job = importHold("PDF");
        when(jobs.saveAndFlush(any(ClaudeFixJob.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        starter.onHeld(job.getId());

        verifyNoInteractions(copies, channel);
        verify(jobs, never()).save(any(ClaudeFixJob.class));
    }

    @Test
    void aSendWhoseBookkeepingFailsLeavesTheReservedRow() {
        ImportJob job = importHold("PDF");
        copyChecks(BlankedCopy.Check.MATCHES);
        when(channel.send(any(), anyInt(), anyString(), anyString())).thenReturn("sha-1");
        when(jobs.save(any(ClaudeFixJob.class))).thenThrow(new IllegalStateException("database blip"));
        when(jobs.existsById(job.getId())).thenAnswer(inv -> rows.containsKey(job.getId()));

        starter.onHeld(job.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.values().iterator().next().getJobId()).isEqualTo(job.getId());
    }

    @Test
    void anExceptionWhileRecordingTheErrorIsSwallowedToo() {
        ImportJob job = importHold("PDF");
        when(copies.make(any())).thenThrow(new IllegalStateException("boom"));
        when(jobs.save(any(ClaudeFixJob.class))).thenThrow(new IllegalStateException("database down"));

        starter.onHeld(job.getId());
    }
}
