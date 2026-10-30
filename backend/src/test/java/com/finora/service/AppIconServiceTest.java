package com.finora.service;

import com.finora.dto.AppIconDtos.AppIconDto;
import com.finora.dto.AppIconDtos.AppIconScheduleSaveRequest;
import com.finora.entity.AppIconSchedule;
import com.finora.exception.ApiException;
import com.finora.notification.campaign.IstClock;
import com.finora.repository.AppIconScheduleRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AppIconWindowSource.IconWindow;
import com.finora.service.AppIconWindowSource.IconWindows;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The time rules behind the public answer, at their exact edges, plus the save rules that do not
 * need a database. HTTP, the cache and the exclusion constraint are covered by AppIconControllerIT.
 */
class AppIconServiceTest {

    /** Diwali 2026 is Sunday 8 November. A window from 00:00 IST that day to 00:00 IST on the 10th. */
    private static final Instant DIWALI_START = IstClock.at(LocalDate.of(2026, 11, 8), LocalTime.MIDNIGHT);
    private static final Instant DIWALI_END = IstClock.at(LocalDate.of(2026, 11, 10), LocalTime.MIDNIGHT);

    private final AppIconScheduleRepository repository = mock(AppIconScheduleRepository.class);
    private final AppIconWindowSource windowSource = mock(AppIconWindowSource.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private AppIconService serviceAt(Instant now) {
        return new AppIconService(repository, windowSource, userRepository, auditService, IstClock.fixedAt(now));
    }

    private void windows(IconWindow... windows) {
        when(windowSource.openWindows()).thenReturn(new IconWindows(new ArrayList<>(List.of(windows))));
    }

    @Test
    void theIstMidnightInstantsAreWhatTheyClaim() {
        assertThat(DIWALI_START).isEqualTo(Instant.parse("2026-11-07T18:30:00Z"));
        assertThat(DIWALI_END).isEqualTo(Instant.parse("2026-11-09T18:30:00Z"));
    }

    @Test
    void noWindows_isTheDefaultIcon() {
        windows();
        assertThat(serviceAt(DIWALI_START).current()).isEqualTo(new AppIconDto("default", null));
    }

    @Test
    void oneSecondBeforeTheStart_isStillTheDefault() {
        windows(new IconWindow("diwali", DIWALI_START, DIWALI_END));
        assertThat(serviceAt(DIWALI_START.minusSeconds(1)).current().icon()).isEqualTo("default");
    }

    @Test
    void exactlyAtTheStart_theFestivalIconShows_untilTheEnd() {
        windows(new IconWindow("diwali", DIWALI_START, DIWALI_END));
        assertThat(serviceAt(DIWALI_START).current()).isEqualTo(new AppIconDto("diwali", DIWALI_END));
    }

    @Test
    void oneSecondBeforeTheEnd_theFestivalIconStillShows() {
        windows(new IconWindow("diwali", DIWALI_START, DIWALI_END));
        assertThat(serviceAt(DIWALI_END.minusSeconds(1)).current().icon()).isEqualTo("diwali");
    }

    @Test
    void exactlyAtTheEnd_itRevertsToTheDefault_evenWhileTheWindowIsStillCached() {
        windows(new IconWindow("diwali", DIWALI_START, DIWALI_END));
        assertThat(serviceAt(DIWALI_END).current()).isEqualTo(new AppIconDto("default", null));
    }

    @Test
    void backToBackWindows_handOverAtTheBoundary() {
        Instant next = DIWALI_END.plusSeconds(86_400);
        windows(new IconWindow("diwali", DIWALI_START, DIWALI_END), new IconWindow("diwali", DIWALI_END, next));
        assertThat(serviceAt(DIWALI_END).current()).isEqualTo(new AppIconDto("diwali", next));
    }

    @Test
    void aStoredKeyTheCodeNoLongerKnows_fallsBackToTheDefault() {
        windows(new IconWindow("retired-icon", DIWALI_START, DIWALI_END));
        assertThat(serviceAt(DIWALI_START).current()).isEqualTo(new AppIconDto("default", null));
    }

    @Test
    void aStoredDefaultKey_answersDefaultWithNoUntil() {
        windows(new IconWindow("default", DIWALI_START, DIWALI_END));
        assertThat(serviceAt(DIWALI_START).current()).isEqualTo(new AppIconDto("default", null));
    }

    @Test
    void anEndExactlyNow_isRefusedAsAlreadyOver() {
        AppIconService service = serviceAt(DIWALI_END);
        AppIconScheduleSaveRequest request = new AppIconScheduleSaveRequest("diwali", "Diwali", DIWALI_START, DIWALI_END, null);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aLabelWithAControlCharacter_isRefused() {
        AppIconService service = serviceAt(DIWALI_START.minusSeconds(60));
        AppIconScheduleSaveRequest request = new AppIconScheduleSaveRequest("diwali", "Diwali\u0007", DIWALI_START, DIWALI_END, null);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void theLabelIsSavedTrimmed() {
        AppIconService service = serviceAt(DIWALI_START.minusSeconds(60));
        when(repository.findOverlapping(any(), any(), any())).thenReturn(List.of());
        // Like a real flush: persisting sets the @Version column.
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            AppIconSchedule row = inv.getArgument(0);
            ReflectionTestUtils.setField(row, "version", 0L);
            return row;
        });

        assertThat(service.create(UUID.randomUUID(),
                new AppIconScheduleSaveRequest("diwali", "  Diwali 2026  ", DIWALI_START, DIWALI_END, null)).label())
                .isEqualTo("Diwali 2026");
    }

    @Test
    void aRaceThatHitsTheExclusionConstraint_isA409_andNotAudited() {
        AppIconService service = serviceAt(DIWALI_START.minusSeconds(60));
        when(repository.findOverlapping(any(), any(), any())).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("app_icon_schedule_no_overlap"));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(),
                new AppIconScheduleSaveRequest("diwali", "Diwali", DIWALI_START, DIWALI_END, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(auditService, never()).record(any(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void anUpdateWithoutExpectedVersion_isRefused() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new AppIconSchedule(id, UUID.randomUUID(), DIWALI_START)));
        AppIconService service = serviceAt(DIWALI_START.minusSeconds(60));

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), id,
                new AppIconScheduleSaveRequest("diwali", "Diwali", DIWALI_START, DIWALI_END, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(auditService, never()).record(any(), eq("APP_ICON_SCHEDULE_UPDATED"), anyString(), any(), anyMap());
    }

    @Test
    void theAdminSeesOnlyTheFestivalIcons() {
        assertThat(serviceAt(DIWALI_START).icons()).extracting(o -> o.key()).containsExactly("diwali");
    }
}
