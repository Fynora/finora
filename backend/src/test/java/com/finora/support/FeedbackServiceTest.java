package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.User;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito, matching {@code SupportTicketServiceTest}'s own precedent -- no ownership branching or
 * status matrix here to need a real Postgres for; {@code countGrouped()}'s own aggregation query is
 * covered against a real database separately, by {@code SupportRepositoryIT}.
 */
class FeedbackServiceTest {

    private FeedbackEntryRepository repository;
    private ClientIdentity clientIdentity;
    private UserRepository userRepository;
    private SupportAdminAlertService adminAlerts;
    private SupportSubmissionLimiter submissionLimiter;
    private FeedbackService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(FeedbackEntryRepository.class);
        clientIdentity = mock(ClientIdentity.class);
        userRepository = mock(UserRepository.class);
        adminAlerts = mock(SupportAdminAlertService.class);
        submissionLimiter = mock(SupportSubmissionLimiter.class);
        service = new FeedbackService(repository, clientIdentity, userRepository, adminAlerts, submissionLimiter);

        when(repository.save(any(FeedbackEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(clientIdentity.platform()).thenReturn(ClientPlatform.MOBILE_ANDROID);
        when(clientIdentity.appVersion()).thenReturn("1.0.0");
    }

    @Test
    void submit_setsSourceAndAppVersionFromClientIdentity_notFromTheCaller() {
        FeedbackDto.Summary result = service.submit(userId, FeedbackEntry.Type.BUG, FeedbackEntry.Context.IMPORT_FLOW, "  broken  ");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.type()).isEqualTo(FeedbackEntry.Type.BUG);
        assertThat(result.context()).isEqualTo(FeedbackEntry.Context.IMPORT_FLOW);
        assertThat(result.source()).isEqualTo(ClientPlatform.MOBILE_ANDROID);
        // Trimmed, same as SupportTicketService's own field handling.
        assertThat(result.message()).isEqualTo("broken");
    }

    @Test
    void submit_tellsTheAdmins_withTypeContextAndSourceButNeitherTheMessageNorTheSender() {
        // No transaction here, so AfterCommit runs the alert immediately -- the real call, not a stub.
        service.submit(userId, FeedbackEntry.Type.BUG, FeedbackEntry.Context.IMPORT_FLOW, "broken");

        verify(adminAlerts).alertFeedbackReceived(eq(FeedbackEntry.Type.BUG), eq(FeedbackEntry.Context.IMPORT_FLOW),
                eq(ClientPlatform.MOBILE_ANDROID), any());
    }

    @Test
    void submit_checksTheDailyLimitForThatAccount() {
        service.submit(userId, FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, "x");

        verify(submissionLimiter).requireFeedbackAllowance(userId);
    }

    @Test
    void anAccountOverItsDailyLimit_getsA429_andNothingIsSavedOrAnnounced() {
        org.mockito.Mockito.doThrow(new com.finora.exception.ApiException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "limit"))
                .when(submissionLimiter).requireFeedbackAllowance(userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.submit(userId, FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, "x"))
                .isInstanceOf(com.finora.exception.ApiException.class);

        verify(repository, org.mockito.Mockito.never()).save(any(FeedbackEntry.class));
        org.mockito.Mockito.verifyNoInteractions(adminAlerts);
    }

    @Test
    void submit_stillSucceeds_whenTheAlertCannotStart() {
        org.mockito.Mockito.doThrow(new RuntimeException("executor shut down")).when(adminAlerts)
                .alertFeedbackReceived(any(), any(), any(), any());

        FeedbackDto.Summary result = service.submit(userId, FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, "x");

        assertThat(result.message()).isEqualTo("x");
    }

    @Test
    void adminList_delegatesToTheFilteredRepositoryQuery() {
        Page<FeedbackEntry> page = new PageImpl<>(List.of());
        when(repository.findForAdmin(eq(FeedbackEntry.Type.BUG), eq(FeedbackEntry.Context.HELP), any())).thenReturn(page);

        PagedResponse<FeedbackDto.AdminRow> result = service.adminList(FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, 0, 25);

        assertThat(result.content()).isEmpty();
        verify(repository).findForAdmin(eq(FeedbackEntry.Type.BUG), eq(FeedbackEntry.Context.HELP), any());
        // An empty page must not cost a users query.
        verify(userRepository, never()).findAllById(any());
    }

    private FeedbackEntry entry(UUID senderId, String message) {
        FeedbackEntry e = new FeedbackEntry();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setUserId(senderId);
        e.setType(FeedbackEntry.Type.BUG);
        e.setContext(FeedbackEntry.Context.OTHER);
        e.setSource(ClientPlatform.WEB);
        e.setMessage(message);
        e.setAppVersion("1.0.0");
        return e;
    }

    @Test
    void adminList_namesWhoSentEachEntry_withOneBatchedUserLookupForThePage() {
        UUID otherUserId = UUID.randomUUID();
        User sender = new User();
        ReflectionTestUtils.setField(sender, "id", userId);
        sender.setFullName("Test Sender");
        sender.setEmail("sender@example.com");
        User other = new User();
        ReflectionTestUtils.setField(other, "id", otherUserId);
        other.setFullName("Other Sender");
        other.setEmail("other@example.com");
        // The same user sending twice must still be looked up once.
        Page<FeedbackEntry> page = new PageImpl<>(List.of(
                entry(userId, "first"), entry(otherUserId, "second"), entry(userId, "third")));
        when(repository.findForAdmin(any(), any(), any())).thenReturn(page);
        when(userRepository.findAllById(any())).thenReturn(List.of(sender, other));

        PagedResponse<FeedbackDto.AdminRow> result = service.adminList(null, null, 0, 25);

        assertThat(result.content()).extracting("message", "userName", "userEmail")
                .containsExactly(
                        tuple("first", "Test Sender", "sender@example.com"),
                        tuple("second", "Other Sender", "other@example.com"),
                        tuple("third", "Test Sender", "sender@example.com"));
        assertThat(result.content()).extracting("userId").containsExactly(userId, otherUserId, userId);
        verify(userRepository, times(1)).findAllById(any());
    }

    @Test
    void adminList_keepsTheRowWhenItsSenderIsNotAvailable() {
        // No user id at all (the column is nullable by design), and a user id whose row is gone.
        UUID goneUserId = UUID.randomUUID();
        Page<FeedbackEntry> page = new PageImpl<>(List.of(entry(null, "anonymous"), entry(goneUserId, "orphaned")));
        when(repository.findForAdmin(any(), any(), any())).thenReturn(page);
        when(userRepository.findAllById(any())).thenReturn(List.of());

        PagedResponse<FeedbackDto.AdminRow> result = service.adminList(null, null, 0, 25);

        assertThat(result.content()).extracting("message", "userName", "userEmail")
                .containsExactly(tuple("anonymous", null, null), tuple("orphaned", null, null));
        assertThat(result.content()).extracting("userId").containsExactly(null, goneUserId);
    }

    private FeedbackEntryRepository.FeedbackBreakdown row(
            FeedbackEntry.Type type, FeedbackEntry.Context context, ClientPlatform source, long total) {
        FeedbackEntryRepository.FeedbackBreakdown row = mock(FeedbackEntryRepository.FeedbackBreakdown.class);
        when(row.getType()).thenReturn(type);
        when(row.getContext()).thenReturn(context);
        when(row.getSource()).thenReturn(source);
        when(row.getTotal()).thenReturn(total);
        return row;
    }

    @Test
    void breakdown_foldsTheFlatGroupingIntoThreeDimensionTalliesAndAGrandTotal() {
        // Extracted to locals first, not built inline inside List.of() -- nesting mock() calls
        // inside another when(...).thenReturn(...)'s argument evaluation interleaves Mockito's
        // stubbing state and throws UnfinishedStubbingException (see row()'s own when() calls).
        FeedbackEntryRepository.FeedbackBreakdown row1 = row(FeedbackEntry.Type.BUG, FeedbackEntry.Context.IMPORT_FLOW, ClientPlatform.WEB, 5);
        FeedbackEntryRepository.FeedbackBreakdown row2 = row(FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, ClientPlatform.WEB, 2);
        FeedbackEntryRepository.FeedbackBreakdown row3 = row(FeedbackEntry.Type.FEATURE_REQUEST, FeedbackEntry.Context.IMPORT_FLOW, ClientPlatform.MOBILE_ANDROID, 3);
        when(repository.countGrouped()).thenReturn(List.of(row1, row2, row3));

        FeedbackDto.Breakdown result = service.breakdown();

        assertThat(result.total()).isEqualTo(10);
        // Highest count first within each dimension.
        assertThat(result.byType()).extracting("label", "total")
                .containsExactly(tuple("BUG", 7L), tuple("FEATURE_REQUEST", 3L));
        assertThat(result.byContext()).extracting("label", "total")
                .containsExactly(tuple("IMPORT_FLOW", 8L), tuple("HELP", 2L));
        assertThat(result.bySource()).extracting("label", "total")
                .containsExactly(tuple("WEB", 7L), tuple("MOBILE_ANDROID", 3L));
    }

    @Test
    void breakdown_tiedCounts_breakAlphabeticallyByLabel_deterministicRegardlessOfHashOrder() {
        // groupingBy() collects into a HashMap -- with no tie-break, two equal counts could swap
        // order between requests on hash-bucket layout alone, nothing about the underlying data
        // having changed. Repeated 20 times: a flaky, order-dependent tie-break would eventually
        // produce FEATURE_REQUEST-before-BUG and fail this, where a single run could pass by luck.
        for (int i = 0; i < 20; i++) {
            FeedbackEntryRepository.FeedbackBreakdown row1 = row(FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, ClientPlatform.WEB, 3);
            FeedbackEntryRepository.FeedbackBreakdown row2 = row(FeedbackEntry.Type.FEATURE_REQUEST, FeedbackEntry.Context.HELP, ClientPlatform.WEB, 3);
            when(repository.countGrouped()).thenReturn(List.of(row1, row2));

            FeedbackDto.Breakdown result = service.breakdown();

            assertThat(result.byType()).extracting("label")
                    .as("iteration %d", i)
                    .containsExactly("BUG", "FEATURE_REQUEST");
        }
    }

    @Test
    void breakdown_onNoFeedbackAtAll_returnsZeroTotalAndEmptyTallies() {
        when(repository.countGrouped()).thenReturn(List.of());

        FeedbackDto.Breakdown result = service.breakdown();

        assertThat(result.total()).isZero();
        assertThat(result.byType()).isEmpty();
        assertThat(result.byContext()).isEmpty();
        assertThat(result.bySource()).isEmpty();
    }
}
