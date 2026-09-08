import type { QueryClient } from '@tanstack/react-query';

/**
 * The cascading-refresh set that any write to a transaction, account, or import must trigger.
 *
 * Ported from the identical list duplicated in frontend/src/pages/Ledger.tsx
 * (invalidateEverything) and Import.tsx. Centralized here because the web copies have already
 * drifted apart once -- and the easiest key to forget is exactly the one whose screen isn't
 * visible from where the edit happened: 'report'/'report-months' feed the Dashboard's cash-flow
 * chart, which the Ledger doesn't render but every edit changes the totals of.
 */
export const FINANCIAL_QUERY_KEYS = [
  'transactions',
  'dashboard-summary',
  'accounts',
  'recent-transactions',
  'budgets',
  'goals',
  'insights',
  'report-months',
  'report',
  'statement-imports',
  // Both added with the Phase 4 screens, and both are exactly the case this module's comment
  // warns about -- a key whose screen isn't visible from where the edit happens. Every balance
  // change moves net worth, and every transaction change can create or break a recurring-payment
  // pattern; without these, the Investments and Insights screens keep showing pre-import figures
  // until their caches age out on their own.
  'networth',
  'recurring',
  // Settings' Data section: statements imported, transactions imported/skipped, last import.
  // Every one of those is a direct count of the thing an import or a statement deletion changes.
  'import-statistics',
  // The categorization review backlog and its merchant-grouped half. Both shrink when a category
  // is set anywhere -- including from the Ledger, which is a different screen than the one showing
  // the queue, and exactly the "key whose screen isn't visible from where the edit happens" case
  // this module's comment warns about. An import also refills them, so they belong in the cascade
  // in both directions.
  'needs-review',
  'needs-review-groups',
  // Phase 4. Third partition of the same backlog as the two keys above -- shrinks whenever a
  // category is set anywhere, including bulk-applying a merchant group or a single row (a person's
  // rows and a merchant's rows are disjoint sets, but resolving either can be the last thing
  // keeping a THIRD, unrelated counterparty group's own count meaningful on screen).
  'needs-review-by-counterparty',
  // Unfinished (staged, not yet confirmed) imports. Confirming one consumes it, so the "continue a
  // previous import" list on the Import screen is stale the moment an import lands -- and that list
  // is on a DIFFERENT step than the confirm that empties it, which is exactly the case this
  // module's comment warns about.
  'import-sessions',
  // Getting-started checklist (D-onboarding). 3 of its 6 items are derived directly from
  // ImportJob/Budget/Goal existence on the backend -- importing a first statement, creating a
  // budget, or creating a goal each flips one of them from incomplete to complete. Without this,
  // ChecklistWidget on the Dashboard would keep showing pre-write state until its cache happened
  // to age out on its own, exactly the bug class this module's comment warns about.
  'onboarding',
  // Mobile Phase 3 (Advanced Reports). Every one of these five is computed directly from the
  // user's transaction history (top merchants/categories by spend, monthly spend trend, per-
  // category confidence, learned-vs-corrected counts) -- exactly the shape every other key in
  // this list already covers, not an exception to it. AdvancedReportsScreen happens to remount
  // fresh (default staleTime: 0, and React Navigation drops a popped native-stack screen) every
  // time it's navigated to, so in practice a stale cache here is short-lived either way -- but
  // relying on that incidentally, the way web's identical AdvancedReports.tsx queries currently
  // do (not in frontend's own invalidateEverything/Import.tsx cascade), is exactly the implicit
  // assumption this module's whole design exists to replace with an explicit one.
  'advanced-reports-top-merchants',
  'advanced-reports-top-categories',
  'advanced-reports-trend',
  'advanced-reports-confidence',
  'advanced-reports-learning-growth',
  // Phase 4 (Medium-Tier Parity). Unlike 'transaction-source' (excluded below -- which statement
  // row a transaction came from is fixed at import time), a transaction's EXPLANATION genuinely
  // changes on a write: recategorizing it from the Ledger sets a new decisionSource/summary
  // (MANUAL, not whatever the engine originally guessed), and confirmNotDuplicate changes what its
  // reconciliation section says. Cached keyed by transaction id
  // (['transaction-explanation', id]), so this partial key invalidates every cached id at once --
  // reopening "Why this category?" for a row just edited elsewhere must not show the pre-edit
  // reasoning.
  'transaction-explanation',
] as const;

export function invalidateFinancialData(queryClient: QueryClient) {
  FINANCIAL_QUERY_KEYS.forEach((key) => {
    void queryClient.invalidateQueries({ queryKey: [key] });
  });
}
