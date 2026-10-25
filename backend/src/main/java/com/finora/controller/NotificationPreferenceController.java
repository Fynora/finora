package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.notification.api.NotificationPreferenceService;
import com.finora.notification.api.NotificationPreferenceService.PreferenceView;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The current user's own notification preferences -- the settings screen's toggles, and what the
 * "manage these emails" line in a FINANCIAL email points to. See
 * {@link NotificationPreferenceService} for which preferences are exposed and why. The user id is
 * always {@link CurrentUser#id()}, never a request field.
 */
@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;
    private final CurrentUser currentUser;

    public NotificationPreferenceController(NotificationPreferenceService service,
            CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public record UpdatePreferenceRequest(@NotNull NotificationCategory category,
            @NotNull NotificationChannel channel, @NotNull Boolean enabled) {
    }

    /**
     * @param categories which categories to list; FINANCIAL alone when absent. Gate 2b: clients that
     *     draw the REMINDERS switch ask for {@code FINANCIAL,REMINDERS}; older ones ask for nothing and
     *     see exactly what they always saw (NotificationPreferenceService#list(UUID, Set)).
     */
    @GetMapping
    public ApiResponse<List<PreferenceView>> list(
            @RequestParam(value = "categories", required = false) List<NotificationCategory> categories) {
        return ApiResponse.ok(service.list(currentUser.id(), listed(categories)));
    }

    @PutMapping
    public ApiResponse<List<PreferenceView>> update(
            @Valid @RequestBody UpdatePreferenceRequest request,
            @RequestParam(value = "categories", required = false) List<NotificationCategory> categories) {
        return ApiResponse.ok(service.set(currentUser.id(), request.category(), request.channel(),
                request.enabled(), listed(categories)), "Notification preference saved");
    }

    private static Set<NotificationCategory> listed(List<NotificationCategory> categories) {
        return categories == null || categories.isEmpty()
                ? Set.of(NotificationCategory.FINANCIAL)
                : Set.copyOf(categories);
    }
}
