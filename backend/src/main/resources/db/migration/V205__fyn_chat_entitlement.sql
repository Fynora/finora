-- Fyn Phase 6: closes the pricing/scope gap FeatureEntitlement.java's own doc comment on FYN_CHAT
-- flagged since Phase 1 -- "Deliberately no seed migration yet ... until the pricing/scope
-- decision in that plan's §7 item 1 is made and a seed migration is added."
--
-- 2026-09-14 costing decision, granted to all three plans: Free gets FYN_CHAT too, not the
-- all-or-nothing gate every other Fyn surface still has -- FynChatOrchestrationService rations it
-- to a small daily question count instead (see that class's freeDailyQuestionLimitReached), same
-- shape as ACCOUNT_LIMIT_REACHED's "come back with more room, or upgrade" rather than a hard wall.
-- Plus/Premium are granted with no question-count cap, backed only by FynCostGovernanceService's
-- existing per-user dollar cap ($1.00/day default -- roughly 660 messages at real Haiku 4.5 usage,
-- see FynProperties' own doc comment -- nowhere near real human usage, so uncapped is safe as
-- configured today without a fresh per-message-count limit on top of it).
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'FYN_CHAT', true FROM plans WHERE code IN ('FREE', 'PLUS', 'PREMIUM');
