package com.finora.imports;

import com.finora.entity.LayoutProfile;
import com.finora.entity.RegisteredLayout;
import com.finora.exception.ApiException;
import com.finora.repository.LayoutProfileRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.service.AuditService;
import com.finora.service.BankManagementService;
import com.finora.util.BankRegistry;
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
    private final BankManagementService bankManagementService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.finora.imports.blanked.FlaggedLayoutUploadService flaggedUploads;

    public LayoutCurationService(RegisteredLayoutRepository layoutRepository,
                                 LayoutProfileRepository profileRepository,
                                 AuditService auditService,
                                 LayoutProfileAutoLinker autoLinker,
                                 LayoutProfileBackfill backfill,
                                 jakarta.persistence.EntityManager entityManager,
                                 BankManagementService bankManagementService,
                                 org.springframework.jdbc.core.JdbcTemplate jdbc,
                                 com.finora.imports.blanked.FlaggedLayoutUploadService flaggedUploads) {
        this.flaggedUploads = flaggedUploads;
        this.backfill = backfill;
        this.jdbc = jdbc;
        this.bankManagementService = bankManagementService;
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
            String reviewAnalysisReference,
            /** Each current reason's source: the analysis reference of the upload that first raised
             *  it in this flag (V270). Empty for a layout flagged before V270. */
            Map<String, String> reviewReasonSources,
            List<String> acknowledgedReasons,
            UUID profileId, String profileName, Integer profileVersion,
            /** "AUTO", "MANUAL", or null when nobody has decided this layout's profile yet. */
            String profileLinkSource,
            /** The version it had before an earlier-seen layout joined and moved it up (V245), and
             *  when; both null when it has not moved in its current profile. */
            Integer previousProfileVersion, Instant profileVersionChangedAt,
            /** Whether a blanked copy can be made of an upload the flag names, which one, and if
             *  none can, why. Filled for the review queue only; null in every other listing, meaning
             *  not checked. */
            ReviewCopy reviewCopy) {}

    /**
     * {@code reference} is the upload a copy would be made of when {@code available}: the latest
     * flagged upload ({@code reviewAnalysisReference}) when it can be copied, otherwise the newest
     * upload that raised a reason the flag still carries and can be copied. {@code refusal} says why
     * no copy can be made, as a code, when {@code available} is false: the latest flagged upload's
     * refusal ({@link com.finora.imports.blanked.FlaggedLayoutUploadService.Refusal}), or NO_UPLOAD
     * when the flag recorded no upload. A code, not the copy's own words: those can name a
     * customer's choice, and this listing carries no personal data.
     */
    public record ReviewCopy(boolean available, String refusal, String reference) {}

    /** A profile and its layouts, in version order. {@code automatic} is true for a profile the
     *  engine groups into by bank and account family. */
    /** How many confirmed imports of a layout whose bank was unknown (no file bank, no profile bank)
     *  ended with the user choosing {@code bankId}. A hint for attaching the layout to a profile;
     *  counts only (spec 2026-10-22, section 6). */
    public record BankChoiceCount(String fingerprint, String bankId, String bankName, long count) {}

    /** One week (Monday, India time) of accounts created by statement imports, by where their bank
     *  came from (spec 2026-10-22, section 8). */
    public record BankOriginWeek(java.time.LocalDate weekStart, long fromFile, long fromProfile,
                                 long chosenByUser, long otherBank) {}

    /** {@code bankId}/{@code bankName}: the bank this profile's layouts give an upload whose file names
     *  none (V285), or null when no bank is set yet. */
    public record ProfileView(UUID id, String name, boolean automatic, String bankId, String bankName,
                              List<RegistryEntry> versions) {}

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
        // Each flagged layout's latest upload is checked the way a blanked copy of it would be,
        // before anything is read, so the portal offers the copy only where it can be made. The
        // queue is short (a handful of layouts), so a few lookups each is fine.
        return layoutRepository.findByNeedsReviewTrueOrderByReviewFlaggedAtAsc().stream()
                .map(layout -> entryOf(layout, profileNames, reviewCopyOf(layout))).toList();
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
            views.add(viewOf(profile, versions));
        }
        views.sort(Comparator.comparing(v -> v.name().toLowerCase(java.util.Locale.ROOT)));
        return views;
    }

    @Transactional(readOnly = true)
    public List<BankChoiceCount> bankChoices() {
        return jdbc.query("""
                SELECT layout_fingerprint, chosen_bank_id, count(*) AS n FROM import_bank_choice
                WHERE bank_source = 'NONE' AND layout_fingerprint IS NOT NULL
                GROUP BY layout_fingerprint, chosen_bank_id
                ORDER BY layout_fingerprint, n DESC, chosen_bank_id
                """, (rs, i) -> new BankChoiceCount(rs.getString(1), rs.getString(2), bankNameOf(rs.getString(2)),
                rs.getLong(3)));
    }

    private static final java.time.ZoneId WEEK_ZONE = java.time.ZoneId.of("Asia/Kolkata");

    /** The last {@code weeks} weeks (clamped to 1..52) up to and including the current one, newest
     *  first, empty weeks included as zeros. */
    @Transactional(readOnly = true)
    public List<BankOriginWeek> bankOrigins(int weeks) {
        return bankOrigins(weeks, java.time.LocalDate.now(WEEK_ZONE));
    }

    List<BankOriginWeek> bankOrigins(int weeks, java.time.LocalDate today) {
        int span = Math.max(1, Math.min(52, weeks));
        java.time.LocalDate newest = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        java.time.LocalDate oldest = newest.minusWeeks(span - 1L);
        // Classification, in order: the account ended with no bank; the user chose it (the bank was
        // unknown, or they replaced the one we offered); otherwise where the offered bank came from.
        Map<java.time.LocalDate, long[]> byWeek = new HashMap<>();
        jdbc.query("""
                SELECT date_trunc('week', created_at AT TIME ZONE 'Asia/Kolkata')::date AS week,
                       count(*) FILTER (WHERE chosen_bank_id <> 'OTHER' AND bank_source = 'FILE' AND chosen_bank_id = detected_bank_id),
                       count(*) FILTER (WHERE chosen_bank_id <> 'OTHER' AND bank_source = 'PROFILE' AND chosen_bank_id = detected_bank_id),
                       count(*) FILTER (WHERE chosen_bank_id <> 'OTHER' AND (bank_source = 'NONE' OR chosen_bank_id <> detected_bank_id)),
                       count(*) FILTER (WHERE chosen_bank_id = 'OTHER')
                FROM import_bank_choice
                WHERE created_at >= (?::date)::timestamp AT TIME ZONE 'Asia/Kolkata'
                  AND created_at < ((?::date) + 7)::timestamp AT TIME ZONE 'Asia/Kolkata'
                GROUP BY 1
                """, rs -> {
                    byWeek.put(rs.getDate(1).toLocalDate(),
                            new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
                }, oldest, newest);
        List<BankOriginWeek> result = new ArrayList<>(span);
        for (java.time.LocalDate week = newest; !week.isBefore(oldest); week = week.minusWeeks(1)) {
            long[] c = byWeek.getOrDefault(week, new long[4]);
            result.add(new BankOriginWeek(week, c[0], c[1], c[2], c[3]));
        }
        return result;
    }

    private String bankNameOf(String bankId) {
        if (bankId == null || BankRegistry.UNKNOWN_ID.equals(bankId)) return "Other bank";
        String name = bankManagementService.resolve(bankId).officialName();
        return name == null ? bankId : name;
    }

    @Transactional
    public RegistryEntry resolveReview(UUID actingAdminId, String fingerprint) {
        RegisteredLayout layout = requireLayout(fingerprint);
        if (!layout.isNeedsReview()) {
            throw new ApiException(HttpStatus.CONFLICT, "Layout " + fingerprint + " is not waiting for review");
        }
        List<String> resolvedReasons = layout.getReviewReasons();
        layout.resolveReview();
        // Acknowledging starts a new window: the first urgent result after it is alerted once.
        layoutRepository.clearLastImmediateAlert(fingerprint);
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
    public ProfileView createProfile(UUID actingAdminId, String name, String bankId) {
        String trimmed = requireName(name);
        String bank = requireBank(bankId);
        if (profileRepository.existsByNameIgnoreCase(trimmed)) {
            throw new ApiException(HttpStatus.CONFLICT, "A layout profile named \"" + trimmed + "\" already exists");
        }
        LayoutProfile created = new LayoutProfile(trimmed);
        created.setBankId(bank);
        LayoutProfile profile = profileRepository.save(created);
        auditService.record(actingAdminId, "LAYOUT_PROFILE_CREATED", "LayoutProfile", profile.getId(),
                Map.of("name", profile.getName(), "bankId", bank, "actorId", String.valueOf(actingAdminId)));
        return viewOf(profile, List.of());
    }

    /** Renames a profile, sets its bank, or both. Either may be null, not both. A bank change
     *  affects later uploads only: accounts already created keep the bank they got. */
    @Transactional
    public void updateProfile(UUID actingAdminId, UUID profileId, String name, String bankId) {
        if (name == null && bankId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Nothing to update");
        LayoutProfile profile = profileRepository.findById(profileId).orElseThrow(() -> notFoundProfile(profileId));
        if (name != null) {
            String trimmed = requireName(name);
            if (!profile.getName().equalsIgnoreCase(trimmed) && profileRepository.existsByNameIgnoreCase(trimmed)) {
                throw new ApiException(HttpStatus.CONFLICT, "A layout profile named \"" + trimmed + "\" already exists");
            }
            profile.rename(trimmed);
            auditService.record(actingAdminId, "LAYOUT_PROFILE_RENAMED", "LayoutProfile", profile.getId(),
                    Map.of("name", profile.getName(), "actorId", String.valueOf(actingAdminId)));
        }
        if (bankId != null) {
            String bank = requireBank(bankId);
            String previous = profile.getBankId();
            profile.setBankId(bank);
            auditService.record(actingAdminId, "LAYOUT_PROFILE_BANK_SET", "LayoutProfile", profile.getId(),
                    Map.of("bankId", bank, "previousBankId", previous == null ? "" : previous,
                            "actorId", String.valueOf(actingAdminId)));
        }
    }

    /** A profile's bank must be a real one: known to the registry or an admin-added bank, and never
     *  OTHER, which would mean "no bank" while looking like a choice. */
    private String requireBank(String bankId) {
        String trimmed = bankId == null ? "" : bankId.trim();
        if (trimmed.isEmpty() || BankRegistry.UNKNOWN_ID.equals(trimmed)
                || !trimmed.equals(bankManagementService.resolve(trimmed).id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a bank for this profile");
        }
        return trimmed;
    }

    private ProfileView viewOf(LayoutProfile profile, List<RegistryEntry> versions) {
        String bankId = profile.getBankId();
        String bankName = bankId == null ? null : bankManagementService.resolve(bankId).officialName();
        return new ProfileView(profile.getId(), profile.getName(), profile.getAutoKey() != null, bankId, bankName,
                versions);
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
        LayoutProfileAutoLinker.Outcome outcome = backfill.regroup(fingerprint, layout.getSourceFormat(), actingAdminId);
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

    /**
     * The first upload the flag names that a copy can be made of, checked as the copy itself checks
     * (reading nothing, recording nothing): the latest flagged upload, then the uploads that raised
     * its reasons, newest first. The latest one is often the one that can't be copied (the customer
     * deleted it, or it never went through the queue) while an earlier one that raised a reason
     * still can.
     */
    private ReviewCopy reviewCopyOf(RegisteredLayout layout) {
        List<String> candidates = new ArrayList<>();
        String latest = layout.getReviewAnalysisReference();
        if (latest != null) candidates.add(latest);
        layout.getReviewReasonSources().values().stream()
                .filter(ref -> ref != null && !ref.isBlank() && !candidates.contains(ref))
                .distinct()
                .sorted(NEWEST_REFERENCE_FIRST)
                .forEach(candidates::add);
        if (candidates.isEmpty()) return new ReviewCopy(false, "NO_UPLOAD", null);
        String firstRefusal = null;
        for (String reference : candidates) {
            var refusal = flaggedUploads.copyRefusal(reference);
            if (refusal.isEmpty()) return new ReviewCopy(true, null, reference);
            if (firstRefusal == null) firstRefusal = refusal.get().name();
        }
        return new ReviewCopy(false, firstRefusal, null);
    }

    /**
     * Analysis references are {@code SA-<yyyyMMdd>-<sequence>} (StatementAnalysisRecorder), the
     * sequence zero-padded to four digits but not capped, so they are ordered by day and then by
     * sequence as a number, not as text. Anything else sorts after them, by text.
     */
    private static final java.util.regex.Pattern ANALYSIS_REFERENCE = java.util.regex.Pattern.compile("^SA-(\\d{8})-(\\d+)$");

    static final Comparator<String> NEWEST_REFERENCE_FIRST = (a, b) -> {
        long[] ka = referenceKey(a), kb = referenceKey(b);
        if (ka == null || kb == null) {
            if (ka != null) return -1;
            if (kb != null) return 1;
            return b.compareTo(a);
        }
        int byDay = Long.compare(kb[0], ka[0]);
        return byDay != 0 ? byDay : Long.compare(kb[1], ka[1]);
    };

    private static long[] referenceKey(String reference) {
        java.util.regex.Matcher m = ANALYSIS_REFERENCE.matcher(reference);
        if (!m.matches()) return null;
        try {
            return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static RegistryEntry entryOf(RegisteredLayout l, Map<UUID, String> profileNames) {
        return entryOf(l, profileNames, null);
    }

    private static RegistryEntry entryOf(RegisteredLayout l, Map<UUID, String> profileNames, ReviewCopy reviewCopy) {
        return new RegistryEntry(l.getFingerprint(), l.getName(), l.getStatus().name(), l.getSourceFormat(),
                l.getParser(), l.getObservationCount(), l.getStagingCount(), l.getFirstSeen(), l.getLastSeen(),
                l.isNeedsReview(), l.getReviewReasons(), l.getReviewFlaggedAt(), l.getReviewAnalysisReference(),
                l.getReviewReasonSources(), l.getAcknowledgedReasons(), l.getProfileId(),
                l.getProfileId() == null ? null : profileNames.get(l.getProfileId()), l.getProfileVersion(),
                l.getProfileLinkSource(), l.getPreviousProfileVersion(), l.getProfileVersionChangedAt(), reviewCopy);
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
