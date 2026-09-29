-- Makes a layout version renumbering visible (follows V244).
--
-- Versions follow when each layout first appeared (LayoutProfileAutoLinker.place), so a layout that
-- appeared earlier but joins its profile later takes its place and the members after it move up
-- one. A version number an admin has already seen can therefore change. These two columns record
-- the number a layout had before its last move and when it moved, so the admin screen can show
-- "was v2" instead of the number changing silently. Every move is also audited.
ALTER TABLE layout_registry
    ADD COLUMN previous_profile_version    INTEGER,
    ADD COLUMN profile_version_changed_at  TIMESTAMPTZ;
