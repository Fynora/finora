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
    private final LayoutProfileAutoLinker autoLinker;
    private final LayoutProfileBackfill backfill;
    private final jakarta.persistence.EntityManager entityManager;

    public LayoutCurationService(RegisteredLayoutRepository layoutRepository,
                                 LayoutProfileRepository profileRepository,
                                 AuditService auditService,
                                 LayoutProfileAutoLinker autoLinker,
                                 LayoutProfileBackfill backfill,
                                 jakarta.persistence.EntityManager entityManager) {
        this.backfill = backfill;
        this.layoutRepository = layoutRepository;
        this.profileRepository = profileRepository;
        this.auditService = auditService;
        this.autoLinker = autoLinker;
        this.entityManager = entityManager;
    }

    /** One registry row as an admin sees it. {@code profileName} is resolved for display. */
    public record RegistryEntry(
            String fingerprint, String name, String status, String sourceFormat, String parser,
            long observationCount, long stagingCount, Instant firstSeen, Instant lastSeen,
            boolean needsReview, List<String> reviewReasons, Instant reviewFlaggedAt,
            String reviewAnalysisReference, List<String> acknowledgedReasons,
            UUID profileId, String profileName, Integer profileVersion,
            /** "AUTO", "MANUAL", or null when nobody has decided this layout's profile yet. */
            String profileLinkSource,
            /** The version it had before an earlier-seen layout joined and moved it up (V245), and
             *  when; both null when it has not moved in its current profile. */
            Integer previousProfileVersion, Instant profileVersionChangedAt) {}

    /** A profile and its layouts, in version order. {@code automatic} is true for a profile the
     *  engine groups into by bank and account family. */
    public record ProfileView(UUID id, String name, boolean automatic, List<RegistryEntry> versions) {}

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
            views.add(new ProfileView(profile.getId(), profile.getName(), profile.getAutoKey() != null, versions));
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
        return new ProfileView(profile.getId(), profile.getName(), false, List.of());
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
     * Adds a layout to a profile at the version its first appearance earns (see
     * LayoutProfileAutoLinker.place): v1 for an empty profile, and later-seen members move up one
     * when an earlier-seen layout joins. The layout row, then the profile row, are locked -- the
     * order staging uses -- and the unique index on (profile, version) is the backstop. A layout already in this profile keeps its
     * version -- re-linking is a no-op, not a renumbering. Moving from another profile takes the
     * next version here.
     */
    @Transactional
    public RegistryEntry linkToProfile(UUID actingAdminId, String fingerprint, UUID profileId) {
        // Layout first, then profile: the same order staging takes them in -- see
        // RegisteredLayoutRepository.findByFingerprintForUpdate.
        RegisteredLayout layout = requireLayout(fingerprint);
        LayoutProfile profile = profileRepository.findByIdForUpdate(profileId).orElseThrow(() -> notFoundProfile(profileId));
        if (profileId.equals(layout.getProfileId())) {
            // Already there. An operator confirming an automatic link makes it theirs, so the
            // engine will not treat it as its own to reconsider.
            if (!"MANUAL".equals(layout.getProfileLinkSource())) {
                layout.linkToProfile(profileId, layout.getProfileVersion());
                audit(actingAdminId, "LAYOUT_LINKED_TO_PROFILE", layout,
                        Map.of("profileId", profileId.toString(), "version", layout.getProfileVersion(), "confirmedAutomatic", true));
            }
            return entryOf(layout, profileNames());
        }
        UUID previousProfile = layout.getProfileId();
        // The same first-seen placement automatic grouping uses, so a profile reads in the order its
        // layouts appeared however they got there. Written with SQL (it also moves later members up
        // one), so the managed entity is refreshed afterwards rather than trusted.
        int version = autoLinker.place(fingerprint, profileId, "MANUAL", actingAdminId);
        entityManager.refresh(layout);
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
        // Recorded as an operator decision (RegisteredLayout.unlinkFromProfile), so automatic
        // grouping will not put the layout straight back.
        Map<String, Object> metadata = Map.of("profileId", layout.getProfileId().toString(),
                "version", layout.getProfileVersion());
        layout.unlinkFromProfile();
        audit(actingAdminId, "LAYOUT_UNLINKED_FROM_PROFILE", layout, metadata);
        return entryOf(layout, profileNames());
    }

    /**
     * Undoes an operator's decision about a layout's profile -- a manual link or a removal -- and
     * lets automatic grouping place it again straight away from the evidence already stored (the
     * same evidence the startup backfill reads). With no evidence it stays ungrouped until a
     * statement of it is next uploaded, when staging groups it.
     */
    @Transactional
    public RegistryEntry returnToAutomatic(UUID actingAdminId, String fingerprint) {
        RegisteredLayout layout = requireLayout(fingerprint);
        if (!"MANUAL".equals(layout.getProfileLinkSource())) {
            throw new ApiException(HttpStatus.CONFLICT, "Layout " + fingerprint + " is already grouped automatically");
        }
        Map<String, Object> metadata = new HashMap<>();
        if (layout.getProfileId() != null) {
            metadata.put("previousProfileId", layout.getProfileId().toString());
            metadata.put("previousVersion", layout.getProfileVersion());
        }
        layout.returnToAutomatic();
        layoutRepository.flush();
        LayoutProfileAutoLinker.Outcome outcome = backfill.regroup(fingerprint, layout.getSourceFormat());
        entityManager.refresh(layout);
        metadata.put("outcome", outcome.name());
        audit(actingAdminId, "LAYOUT_RETURNED_TO_AUTOMATIC", layout, metadata);
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
                l.getProfileId() == null ? null : profileNames.get(l.getProfileId()), l.getProfileVersion(),
                l.getProfileLinkSource(), l.getPreviousProfileVersion(), l.getProfileVersionChangedAt());
    }

    /** Locked for the rest of the transaction: every caller writes the layout, and staging may be
     *  writing its review flag or profile at the same moment. */
    private RegisteredLayout requireLayout(String fingerprint) {
        return layoutRepository.findByFingerprintForUpdate(fingerprint).orElseThrow(() -> new ApiException(
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
