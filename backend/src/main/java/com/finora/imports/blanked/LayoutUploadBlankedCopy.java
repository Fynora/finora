package com.finora.imports.blanked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A blanked copy that was made of an upload of a layout flagged for review (V313). What the
 * customer's import timeline lists beside the held-statement openings: the day our team opened
 * their statement to fix a reading problem.
 */
@Entity
@Table(name = "layout_upload_blanked_copies")
public class LayoutUploadBlankedCopy {

    @Id private UUID id;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "subject_user_id", nullable = false) private UUID subjectUserId;
    @Column(name = "made_by", nullable = false) private UUID madeBy;
    @Column(name = "analysis_reference", nullable = false) private String analysisReference;
    @Column(name = "made_at", nullable = false) private Instant madeAt;

    protected LayoutUploadBlankedCopy() {}

    public static LayoutUploadBlankedCopy made(UUID jobId, UUID subjectUserId, UUID madeBy, String analysisReference,
                                               Instant madeAt) {
        LayoutUploadBlankedCopy copy = new LayoutUploadBlankedCopy();
        copy.id = UUID.randomUUID();
        copy.jobId = jobId;
        copy.subjectUserId = subjectUserId;
        copy.madeBy = madeBy;
        copy.analysisReference = analysisReference;
        copy.madeAt = madeAt;
        return copy;
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getSubjectUserId() { return subjectUserId; }
    public UUID getMadeBy() { return madeBy; }
    public String getAnalysisReference() { return analysisReference; }
    public Instant getMadeAt() { return madeAt; }
}
