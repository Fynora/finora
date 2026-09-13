-- Fyn Phase 2, import/parsing assist (docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md,
-- §6 Phase 2). Deliberately NOT reusing held_statements.engineer_notes: that column is replaced
-- wholesale by HeldStatementService.addNotes on every call (see that method's own doc), so writing
-- an AI suggestion through it would silently clobber whatever an engineer had already typed. A
-- separate, clearly-attributed column avoids that collision entirely -- this is a considered
-- deviation from the plan's original "no new persistence needed" text, made once the actual
-- addNotes semantics were read.
ALTER TABLE held_statements
    ADD COLUMN ai_suggested_diagnosis TEXT,
    ADD COLUMN ai_suggested_diagnosis_at TIMESTAMPTZ;
