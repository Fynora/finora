-- Release markers for the admin Activation page: dated events such as "Gate 2b live" that the page
-- shows on its weekly table and chart, with a before/after comparison of the signups either side.
--
-- marker_date is a reporting-zone (IST) calendar day, the same unit as a signup's day on that page.
-- Several markers may share a day. created_by is the admin who added it, kept for attribution with no
-- foreign key, same as app_icon_schedule and audit_logs. Nothing here is about any user. The id is a
-- number, not a UUID: the report carries it, and the report holds no UUIDs (ActivationReportIT checks).

CREATE TABLE activation_release_markers (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    marker_date DATE         NOT NULL,
    label       VARCHAR(80)  NOT NULL,
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT activation_release_markers_label_not_blank CHECK (btrim(label) <> '')
);

CREATE INDEX activation_release_markers_date_idx ON activation_release_markers (marker_date);
