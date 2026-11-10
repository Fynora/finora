package com.finora.service;

import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.UserSettings;
import com.finora.exception.ApiException;
import com.finora.repository.UserSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Financial Intelligence Workspace, System Settings module.
 *
 * Scope (confirmed by product owner): exactly one real, persisted, editable setting for now --
 * auto_apply_confidence_threshold -- with every other value on the System Settings page shown
 * read-only as "coming in a future release" (a purely frontend, unpersisted label; nothing to
 * wire up here for those yet).
 *
 * The threshold is live: CategorizationService.needsCategoryReview reads it to decide whether a
 * default ("Other") guess goes to the review queue. A guess whose confidence is at or above it is
 * not flagged, so 0 sends nothing there. That is why a save must carry a real whole number: a
 * missing value used to be read as 0 and stored.
 */
@Service
public class WorkspaceSettingsService {

    private final UserSettingsRepository userSettingsRepository;

    public WorkspaceSettingsService(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    @Transactional(readOnly = true)
    public WorkspaceSettingsDto get(UUID userId) {
        return userSettingsRepository.findByUserId(userId)
                .map(this::toDto)
                // No row yet -- return the schema default without writing anything, so a plain
                // GET (e.g. every page load) never has a side effect. The row is only created on
                // first PUT (see update() below).
                .orElseGet(() -> new WorkspaceSettingsDto(90, null));
    }

    @Transactional
    public WorkspaceSettingsDto update(UUID userId, WorkspaceSettingsDto.UpdateRequest request) {
        Integer threshold = request == null ? null : request.autoApplyConfidenceThreshold();
        if (threshold == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a confidence threshold from 0 to 100.");
        }
        if (threshold < 0 || threshold > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Confidence threshold must be between 0 and 100.");
        }
        userSettingsRepository.upsertThreshold(userId, threshold);
        return userSettingsRepository.findByUserId(userId).map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("user_settings row missing right after its upsert"));
    }

    private WorkspaceSettingsDto toDto(UserSettings s) {
        return new WorkspaceSettingsDto(s.getAutoApplyConfidenceThreshold(), s.getUpdatedAt());
    }
}
