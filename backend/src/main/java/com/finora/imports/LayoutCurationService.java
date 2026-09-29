package com.finora.imports;

import com.finora.entity.LayoutProfile;
import com.finora.entity.RegisteredLayout;
import com.finora.exception.ApiException;
import com.finora.repository.LayoutProfileRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An operator's side of the layout registry (V68, V243): the review queue, naming a layout,
 * setting its support status, and grouping layouts into versioned profiles.
 *
 * <p>Every write is audited with the acting admin. Reads carry fingerprints, names, counts and
 * reason codes only -- nothing about any user or statement, the same line
 * {@code AdminLayoutIntelligenceController} holds.
 */
@Service
public class LayoutCurationService {

    private final RegisteredLayoutRepository layoutRepository;
    private final LayoutProfileRepository profileRepository;
    private final AuditService auditService;

    public LayoutCurationService(RegisteredLayoutRepository layoutRepository,
                                 LayoutProfileRepository profileRepository,
                                 AuditService auditService) {
        this.layoutRepository = layoutRepository;
        this.profileRepository = profileRepository;
        this.auditService = auditService;
    }

    /** One registry row as an admin sees it. {@code profileName} is resolved for display. */
    public record RegistryEntry(
            String fingerprint, String name, String status, String sourceFormat, String parser,
            long observationCount, long stagingCount, Instant firstSeen, Instant lastSeen,
            boolean needsReview, List<String> reviewReasons, Instant reviewFlaggedAt,
            String reviewAnalysisReference, List<String> acknowledgedReasons,
            UUID profileId, String profileName, Integer profileVersion) {}

    /** A profile and its layouts, in version order. */
    public record ProfileView(UUID id, String name, List<RegistryEntry> versions) {}

    @Transactional(readOnly = true)
    public List<RegistryEntry> registry() {
        Map<UUID, String> profileNames = profileNames();
        List<RegistryEntry> entries = new ArrayList<>();
        for (RegisteredLayout layout : layoutRepository.findAll()) entries.add(entryOf(layout, profileNames));
        entries.sort(Comparator.comparing(RegistryEntry::lastSeen).reversed());
        return entries;
    }

    @Transactional(readOnly = true)
    public List<RegistryEntry> reviewQueue() {
        Map<UUID, String> profileNames = profileNames();
        return layoutRepository.findByNeedsReviewTrueOrderByReviewFlaggedAtAsc().stream()
                .map(layout -> entryOf(layout, profileNames)).toList();
    }

    @Transactional(readOnly = true)
    public List<ProfileView> profiles() {
        Map<UUID, String> profileNames = profileNames();
        Map<UUID, List<RegistryEntry>> members = new LinkedHashMap<>();
        for (RegisteredLayout layout : layoutRepository.findAll()) {
            if (layout.getProfileId() == null) continue;
            members.computeIfAbsent(layout.getProfileId(), id -> new ArrayList<>()).add(entryOf(layout, profileNames));
        }
        List<ProfileView> views = new ArrayList<>();
        for (LayoutProfile profile : profileRepository.findAll()) {
            List<RegistryEntry> versions = new ArrayList<>(members.getOrDefault(profile.getId(), List.of()));
            versions.sort(Comparator.comparing(RegistryEntry::profileVersion));
            views.add(new ProfileView(profile.getId(), profile.getName(), versions));
        }
        views.sort(Comparator.comparing(v -> v.name().toLowerCase(java.util.Locale.ROOT)));
        return views;
    }

    @Transactional
    public RegistryEntry resolveReview(UUID actingAdminId, String fingerprint) {
        RegisteredLayout layout = requireLayout(fingerprint);
        if (!layout.isNeedsReview()) {
            throw new ApiException(HttpStatus.CONFLICT, "Layout " + fingerprint + " is not waiting for review");
        }
        List<String> resolvedReasons = layout.getReviewReasons();
        layout.resolveReview();
        audit(actingAdminId, "LAYOUT_REVIEW_RESOLVED", layout, Map.of("reasons", resolvedReasons));
        return entryOf(layout, profileNames());
    }

    @Transactional
    public RegistryEntry update(UUID actingAdminId, String fingerprint, String name, RegisteredLayout.Status status,
                                boolean nameProvided) {
        RegisteredLayout layout = requireLayout(fingerprint);
        Map<String, Object> changes = new HashMap<>();
        if (nameProvided) {
            if (name != null && name.trim().length() > 120) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "A layout name is at most 120 characters");
            }
            layout.rename(name);
            changes.put("name", layout.getName() == null ? "" : layout.getName());
        }
        if (status != null) {
            layout.moveTo(status);
            changes.put("status", status.name());
        }
        if (changes.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to update");
        audit(actingAdminId, "LAYOUT_UPDATED", layout, changes);
        return entryOf(layout, profileNames());
    }

    @Transactional
    public ProfileView createProfile(UUID actingAdminId, String name) {
        String trimmed = requireName(name);
        if (profileRepository.existsByNameIgnoreCase(trimmed)) {
            throw new ApiException(HttpStatus.CONFLICT, "A layout profile named \"" + trimmed + "\" already exists");
        }
        LayoutProfile profile = profileRepository.save(new LayoutProfile(trimmed));
        auditService.record(actingAdminId, "LAYOUT_PROFILE_CREATED", "LayoutProfile", profile.getId(),
                Map.of("name", profile.getName(), "actorId", String.valueOf(actingAdminId)));
        return new ProfileView(profile.getId(), profile.getName(), List.of());
    }

    @Transactional
    public void renameProfile(UUID actingAdminId, UUID profileId, String name) {
        String trimmed = requireName(name);
        LayoutProfile profile = profileRepository.findById(profileId).orElseThrow(() -> notFoundProfile(profileId));
        if (!profile.getName().equalsIgnoreCase(trimmed) && profileRepository.existsByNameIgnoreCase(trimmed)) {
            throw new ApiException(HttpStatus.CONFLICT, "A layout profile named \"" + trimmed + "\" already exists");
        }
        profile.rename(trimmed);
        auditService.record(actingAdminId, "LAYOUT_PROFILE_RENAMED", "LayoutProfile", profile.getId(),
                Map.of("name", profile.getName(), "actorId", String.valueOf(actingAdminId)));
    }

    /**
     * Adds a layout to a profile as its next version (v1 for an empty profile). The profile row is
     * locked first so two operators adding layouts at once get consecutive versions; the unique
     * index on (profile, version) is the backstop. A layout already in this profile keeps its
     * version -- re-linking is a no-op, not a renumbering. Moving from another profile takes the
     * next version here.
     */
    @Transactional
    public RegistryEntry linkToProfile(UUID actingAdminId, String fingerprint, UUID profileId) {
        LayoutProfile profile = profileRepository.findByIdForUpdate(profileId).orElseThrow(() -> notFoundProfile(profileId));
        RegisteredLayout layout = requireLayout(fingerprint);
        if (profileId.equals(layout.getProfileId())) return entryOf(layout, profileNames());
        Integer max = layoutRepository.maxProfileVersion(profileId);
        int version = max == null ? 1 : max + 1;
        UUID previousProfile = layout.getProfileId();
        layout.linkToProfile(profileId, version);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("profileId", profileId.toString());
        metadata.put("profileName", profile.getName());
        metadata.put("version", version);
        if (previousProfile != null) metadata.put("previousProfileId", previousProfile.toString());
        audit(actingAdminId, "LAYOUT_LINKED_TO_PROFILE", layout, metadata);
        return entryOf(layout, profileNames());
    }

    /** Removes a layout from its profile. The versions of the other members are left as they are:
     *  a gap ("v1, v3") is an honest record that a layout was once v2 and was taken out. */
    @Transactional
    public RegistryEntry unlinkFromProfile(UUID actingAdminId, String fingerprint) {
        RegisteredLayout layout = requireLayout(fingerprint);
        if (layout.getProfileId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Layout " + fingerprint + " is not in a profile");
        }
        Map<String, Object> metadata = Map.of("profileId", layout.getProfileId().toString(),
                "version", layout.getProfileVersion());
        layout.unlinkFromProfile();
        audit(actingAdminId, "LAYOUT_UNLINKED_FROM_PROFILE", layout, metadata);
        return entryOf(layout, profileNames());
    }

    private void audit(UUID actingAdminId, String action, RegisteredLayout layout, Map<String, Object> metadata) {
        Map<String, Object> withFingerprint = new HashMap<>(metadata);
        withFingerprint.put("fingerprint", layout.getFingerprint());
        if (actingAdminId != null) withFingerprint.put("actorId", actingAdminId.toString());
        auditService.record(actingAdminId, action, "RegisteredLayout", layout.getId(), withFingerprint);
    }

    private Map<UUID, String> profileNames() {
        Map<UUID, String> names = new HashMap<>();
        for (LayoutProfile p : profileRepository.findAll()) names.put(p.getId(), p.getName());
        return names;
    }

    private static RegistryEntry entryOf(RegisteredLayout l, Map<UUID, String> profileNames) {
        return new RegistryEntry(l.getFingerprint(), l.getName(), l.getStatus().name(), l.getSourceFormat(),
                l.getParser(), l.getObservationCount(), l.getStagingCount(), l.getFirstSeen(), l.getLastSeen(),
                l.isNeedsReview(), l.getReviewReasons(), l.getReviewFlaggedAt(), l.getReviewAnalysisReference(),
                l.getAcknowledgedReasons(), l.getProfileId(),
                l.getProfileId() == null ? null : profileNames.get(l.getProfileId()), l.getProfileVersion());
    }

    private RegisteredLayout requireLayout(String fingerprint) {
        return layoutRepository.findByFingerprint(fingerprint).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "No layout is registered under fingerprint " + fingerprint));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "A profile needs a name");
        String trimmed = name.trim();
        if (trimmed.length() > 120) throw new ApiException(HttpStatus.BAD_REQUEST, "A profile name is at most 120 characters");
        return trimmed;
    }

    private static ApiException notFoundProfile(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "No layout profile " + id);
    }
}
