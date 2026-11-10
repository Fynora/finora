package com.finora.service;

import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.UserSettings;
import com.finora.exception.ApiException;
import com.finora.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkspaceSettingsServiceTest {

    private UserSettingsRepository userSettingsRepository;
    private WorkspaceSettingsService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userSettingsRepository = mock(UserSettingsRepository.class);
        service = new WorkspaceSettingsService(userSettingsRepository);
    }

    @Test
    void get_returnsTheSchemaDefault_whenNoRowExistsYet_withoutWritingAnything() {
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.empty());

        WorkspaceSettingsDto dto = service.get(userId);

        assertThat(dto.autoApplyConfidenceThreshold()).isEqualTo(90);
        assertThat(dto.updatedAt()).isNull();
        verify(userSettingsRepository, never()).save(any());
    }

    @Test
    void get_returnsThePersistedValue_whenARowExists() {
        UserSettings s = new UserSettings();
        s.setUserId(userId);
        s.setAutoApplyConfidenceThreshold(75);
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(s));

        WorkspaceSettingsDto dto = service.get(userId);

        assertThat(dto.autoApplyConfidenceThreshold()).isEqualTo(75);
    }

    /** The row the upsert leaves behind, as findByUserId will then return it. */
    private void upsertStoresRow() {
        UserSettings[] row = new UserSettings[1];
        when(userSettingsRepository.upsertThreshold(eq(userId), anyInt())).thenAnswer(inv -> {
            UserSettings s = new UserSettings();
            s.setUserId(userId);
            s.setAutoApplyConfidenceThreshold(inv.getArgument(1));
            row[0] = s;
            return 1;
        });
        when(userSettingsRepository.findByUserId(userId)).thenAnswer(inv -> Optional.ofNullable(row[0]));
    }

    @Test
    void update_savesWithOneUpsert_andAnswersWithTheStoredRow() {
        upsertStoresRow();

        WorkspaceSettingsDto dto = service.update(userId, new WorkspaceSettingsDto.UpdateRequest(80));

        assertThat(dto.autoApplyConfidenceThreshold()).isEqualTo(80);
        verify(userSettingsRepository).upsertThreshold(userId, 80);
        verify(userSettingsRepository, never()).save(any());
    }

    @Test
    void update_rejectsAThresholdAbove100() {
        assertThatThrownBy(() -> service.update(userId, new WorkspaceSettingsDto.UpdateRequest(101)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(userSettingsRepository);
    }

    @Test
    void update_rejectsANegativeThreshold() {
        assertThatThrownBy(() -> service.update(userId, new WorkspaceSettingsDto.UpdateRequest(-1)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(userSettingsRepository);
    }

    /** A missing value used to be read as 0 and stored -- and 0 takes every guess out of review. */
    @Test
    void update_rejectsAMissingThreshold_insteadOfStoringZero() {
        assertThatThrownBy(() -> service.update(userId, new WorkspaceSettingsDto.UpdateRequest(null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a confidence threshold");
        assertThatThrownBy(() -> service.update(userId, null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(userSettingsRepository);
    }

    @Test
    void update_acceptsTheBoundaryValuesZeroAndOneHundred() {
        upsertStoresRow();

        assertThat(service.update(userId, new WorkspaceSettingsDto.UpdateRequest(0)).autoApplyConfidenceThreshold()).isEqualTo(0);
        assertThat(service.update(userId, new WorkspaceSettingsDto.UpdateRequest(100)).autoApplyConfidenceThreshold()).isEqualTo(100);
    }
}
