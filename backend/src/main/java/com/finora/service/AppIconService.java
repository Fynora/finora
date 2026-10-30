package com.finora.service;

import com.finora.dto.AppIconDtos.AppIconDto;
import com.finora.dto.AppIconDtos.AppIconOptionDto;
import com.finora.dto.AppIconDtos.AppIconScheduleDto;
import com.finora.dto.AppIconDtos.AppIconScheduleSaveRequest;
import com.finora.dto.AppIconDtos.AppIconScheduleState;
import com.finora.entity.AppIcon;
import com.finora.entity.AppIconSchedule;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.campaign.IstClock;
import com.finora.repository.AppIconScheduleRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AppIconWindowSource.IconWindow;
import com.finora.util.AfterCommit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The festival app icon schedule: which home-screen icon the mobile app should show right now, and
 * the admin surface that plans it.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>The icon must be one of {@link AppIcon}'s schedulable keys -- an icon the app bundles.</li>
 *   <li>A window ends after it starts, and its end must still be in the future when saved: a window
 *       that is already over would never show, so saving one is always a mistake.</li>
 *   <li>Windows never overlap. An overlapping save is refused with 409, naming the window it hits.
 *       The table's exclusion constraint (V299) holds this when two saves race; that path is also
 *       answered with 409.</li>
 *   <li>Outside every window the answer is {@link AppIcon#DEFAULT}. So a window ending is the revert:
 *       nothing runs at the end time, the next read simply finds no active window.</li>
 * </ul>
 *
 * <p>Every create, update and delete is written to the audit log with the acting admin, and evicts
 * the public endpoint's cache after commit (see {@link AppIconWindowSource}).
 */
@Service
public class AppIconService {

    private static final DateTimeFormatter IST_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'", Locale.ENGLISH).withZone(IstClock.IST);

    private final AppIconScheduleRepository repository;
    private final AppIconWindowSource windowSource;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final IstClock clock;

    public AppIconService(AppIconScheduleRepository repository, AppIconWindowSource windowSource,
                          UserRepository userRepository, AuditService auditService, IstClock clock) {
        this.repository = repository;
        this.windowSource = windowSource;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * The icon the app should show now. A window is active from its start (inclusive) to its end
     * (exclusive). A stored key the code no longer knows falls back to the default icon rather than
     * sending the app a name it has no icon for.
     */
    public AppIconDto current() {
        Instant now = clock.now();
        for (IconWindow window : windowSource.openWindows().windows()) {
            if (!now.isBefore(window.startsAt()) && now.isBefore(window.endsAt())) {
                return AppIcon.fromKey(window.iconKey())
                        .filter(AppIcon::schedulable)
                        .map(icon -> new AppIconDto(icon.key(), window.endsAt()))
                        .orElseGet(AppIconService::defaultIcon);
            }
        }
        return defaultIcon();
    }

    /** The icons an admin may schedule, in the code's order. */
    public List<AppIconOptionDto> icons() {
        return Arrays.stream(AppIcon.values())
                .filter(AppIcon::schedulable)
                .map(icon -> new AppIconOptionDto(icon.key(), icon.label()))
                .toList();
    }

    /** Every window, newest start first. */
    @Transactional(readOnly = true)
    public List<AppIconScheduleDto> list() {
        List<AppIconSchedule> rows = repository.findAllByOrderByStartsAtDesc();
        Set<UUID> adminIds = new HashSet<>();
        rows.forEach(r -> {
            adminIds.add(r.getCreatedBy());
            adminIds.add(r.getUpdatedBy());
        });
        Map<UUID, String> emails = userRepository.findAllById(adminIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail, (a, b) -> a));
        Instant now = clock.now();
        return rows.stream().map(r -> toDto(r, now, emails)).toList();
    }

    @Transactional
    public AppIconScheduleDto create(UUID adminUserId, AppIconScheduleSaveRequest request) {
        Instant now = clock.now();
        Valid valid = validate(request, now);
        UUID id = UUID.randomUUID();
        refuseOverlap(valid, id);

        AppIconSchedule row = new AppIconSchedule(id, adminUserId, now);
        apply(row, valid);
        AppIconSchedule saved = saveAndFlush(row);

        auditService.record(adminUserId, "APP_ICON_SCHEDULE_CREATED", "AppIconSchedule", saved.getId(),
                describe(saved));
        evictAfterCommit();
        return toDto(saved, now, emailsOf(saved));
    }

    @Transactional
    public AppIconScheduleDto update(UUID adminUserId, UUID id, AppIconScheduleSaveRequest request) {
        AppIconSchedule row = find(id);
        if (request.expectedVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expectedVersion is required when changing a schedule entry.");
        }
        if (!request.expectedVersion().equals(row.getVersion())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Someone else changed this entry since you opened it. Reload the page and try again.");
        }
        Instant now = clock.now();
        Valid valid = validate(request, now);
        refuseOverlap(valid, id);

        Map<String, Object> before = describe(row);
        apply(row, valid);
        row.setUpdatedBy(adminUserId);
        row.setUpdatedAt(now);
        AppIconSchedule saved = saveAndFlush(row);

        Map<String, Object> metadata = new LinkedHashMap<>(describe(saved));
        metadata.put("before", before);
        auditService.record(adminUserId, "APP_ICON_SCHEDULE_UPDATED", "AppIconSchedule", saved.getId(), metadata);
        evictAfterCommit();
        return toDto(saved, now, emailsOf(saved));
    }

    /** Removes a window, whatever its state. Deleting the active one reverts the app to the default icon. */
    @Transactional
    public void delete(UUID adminUserId, UUID id) {
        AppIconSchedule row = find(id);
        Map<String, Object> metadata = describe(row);
        repository.delete(row);
        repository.flush();
        auditService.record(adminUserId, "APP_ICON_SCHEDULE_DELETED", "AppIconSchedule", id, metadata);
        evictAfterCommit();
    }

    // --- internals ---

    private record Valid(AppIcon icon, String label, Instant startsAt, Instant endsAt) {
    }

    private Valid validate(AppIconScheduleSaveRequest request, Instant now) {
        AppIcon icon = AppIcon.fromKey(request.icon())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Unknown icon \"" + request.icon() + "\". Pick one of the icons the app includes."));
        if (!icon.schedulable()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The standard icon shows automatically whenever nothing is scheduled. Pick a festival icon.");
        }
        String label = request.label().strip();
        if (label.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Give the entry a name, for example \"Diwali 2026\".");
        }
        if (label.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The name contains characters that cannot be saved.");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The end must be after the start.");
        }
        if (!request.endsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The end time has already passed, so this icon would never show. Pick a later end.");
        }
        return new Valid(icon, label, request.startsAt(), request.endsAt());
    }

    private void refuseOverlap(Valid valid, UUID selfId) {
        List<AppIconSchedule> clashes = repository.findOverlapping(valid.startsAt(), valid.endsAt(), selfId);
        if (!clashes.isEmpty()) {
            AppIconSchedule other = clashes.get(0);
            throw new ApiException(HttpStatus.CONFLICT, String.format(
                    "This overlaps \"%s\" (%s to %s). Only one icon can be scheduled at a time: "
                            + "change the dates, or edit that entry instead.",
                    other.getLabel(), IST_FORMAT.format(other.getStartsAt()), IST_FORMAT.format(other.getEndsAt())));
        }
    }

    /** Two saves that race past {@link #refuseOverlap} meet the table's exclusion constraint here. */
    private AppIconSchedule saveAndFlush(AppIconSchedule row) {
        try {
            return repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Another entry was saved for an overlapping time just now. Reload the page and check the schedule.");
        }
    }

    private static void apply(AppIconSchedule row, Valid valid) {
        row.setIconKey(valid.icon().key());
        row.setLabel(valid.label());
        row.setStartsAt(valid.startsAt());
        row.setEndsAt(valid.endsAt());
    }

    private AppIconSchedule find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Schedule entry not found: " + id));
    }

    private void evictAfterCommit() {
        AfterCommit.run("app icon schedule cache invalidation", windowSource::evict);
    }

    private Map<UUID, String> emailsOf(AppIconSchedule row) {
        // A HashSet: Set.of throws on the duplicate when one admin both created and last changed it.
        return userRepository.findAllById(new HashSet<>(List.of(row.getCreatedBy(), row.getUpdatedBy()))).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail, (a, b) -> a));
    }

    private static Map<String, Object> describe(AppIconSchedule row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icon", row.getIconKey());
        m.put("label", row.getLabel());
        m.put("startsAt", row.getStartsAt().toString());
        m.put("endsAt", row.getEndsAt().toString());
        return m;
    }

    static AppIconScheduleState stateAt(AppIconSchedule row, Instant now) {
        if (now.isBefore(row.getStartsAt())) return AppIconScheduleState.UPCOMING;
        if (now.isBefore(row.getEndsAt())) return AppIconScheduleState.ACTIVE;
        return AppIconScheduleState.ENDED;
    }

    private static AppIconScheduleDto toDto(AppIconSchedule row, Instant now, Map<UUID, String> emails) {
        String iconLabel = AppIcon.fromKey(row.getIconKey()).map(AppIcon::label).orElse(null);
        return new AppIconScheduleDto(row.getId(), row.getIconKey(), iconLabel, row.getLabel(),
                row.getStartsAt(), row.getEndsAt(), stateAt(row, now),
                row.getCreatedBy(), emails.get(row.getCreatedBy()), row.getCreatedAt(),
                row.getUpdatedBy(), emails.get(row.getUpdatedBy()), row.getUpdatedAt(),
                row.getVersion());
    }

    private static AppIconDto defaultIcon() {
        return new AppIconDto(AppIcon.DEFAULT.key(), null);
    }
}
