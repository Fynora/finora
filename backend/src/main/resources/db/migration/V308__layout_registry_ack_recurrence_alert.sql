-- Acknowledging a layout's review reasons used to silence them for good, by name, however much worse
-- a later run was. A layout whose acknowledged reasons come back with a result that is not explained
-- as harmless now gets one urgent alert per acknowledgement (see
-- docs/superpowers/specs/2026-11-06-layout-alert-acknowledged-reason-design.md). That alert is
-- claimed with last_immediate_alert_at, and resolving a review now clears it.
--
-- Layouts already resolved carry the value V307 gave them. Clear it for every layout that is not
-- flagged, so each is treated as freshly acknowledged. Nothing is sent by this migration: an alert
-- needs a new staging run, and only one whose result is urgent.
UPDATE layout_registry SET last_immediate_alert_at = NULL WHERE NOT needs_review;
