package com.finora.activation;

import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adds and removes the release markers the Activation page shows (V303). A marker is a date and a
 * short label, nothing about any user. Every add and delete is written to the audit log with the
 * acting admin; the marker's id is a number, so it goes in the entry's metadata rather than its
 * entity id (a UUID column).
 */
@Service
public class ReleaseMarkerService {

    static final int MAX_LABEL = 80;
    /** How far ahead a marker may be dated: a planned release, not a typo of the year. */
    static final int MAX_DAYS_AHEAD = 366;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ReportingDay reportingDay;

    public ReleaseMarkerService(JdbcTemplate jdbc, AuditService auditService, ReportingDay reportingDay) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.reportingDay = reportingDay;
    }

    /** A marker as stored. */
    public record Saved(long id, LocalDate date, String label) {}

    @Transactional
    public Saved create(UUID adminUserId, LocalDate date, String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.strip();
        if (date == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose the date the release went live.");
        }
        if (label.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Give the marker a label, such as \"Gate 2b live\".");
        }
        if (label.length() > MAX_LABEL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Keep the label to " + MAX_LABEL + " characters.");
        }
        if (label.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The label can only be plain text on one line.");
        }
        if (date.isAfter(reportingDay.today().plusDays(MAX_DAYS_AHEAD))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That date is more than a year ahead.");
        }
        Long id = jdbc.queryForObject("INSERT INTO activation_release_markers (marker_date, label, created_by, created_at) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class,
                date, label, adminUserId, OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(adminUserId, "ACTIVATION_RELEASE_MARKER_CREATED", "ActivationReleaseMarker", null,
                describe(id, date, label));
        return new Saved(id, date, label);
    }

    @Transactional
    public void delete(UUID adminUserId, long id) {
        List<Saved> found = jdbc.query("SELECT id, marker_date, label FROM activation_release_markers WHERE id = ?",
                (rs, i) -> new Saved(rs.getLong("id"), rs.getObject("marker_date", LocalDate.class),
                        rs.getString("label")), id);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That marker no longer exists.");
        }
        jdbc.update("DELETE FROM activation_release_markers WHERE id = ?", id);
        auditService.record(adminUserId, "ACTIVATION_RELEASE_MARKER_DELETED", "ActivationReleaseMarker", null,
                describe(id, found.get(0).date(), found.get(0).label()));
    }

    private static Map<String, Object> describe(long id, LocalDate date, String label) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("markerId", id);
        metadata.put("date", date.toString());
        metadata.put("label", label);
        return metadata;
    }
}
