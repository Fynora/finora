import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Repeat, TrendingUp, X, Check, Trophy } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import {
  insightsApi, recurringApi, onboardingApi, usageApi, categoriesApi,
  type InsightsData, type RecurringItem, type ChecklistStatus, type CategoryOption,
} from '../api/endpoints';
import { FinoraCard, EmptyState, SectionHeader, Skeleton, Badge } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { useMemoryReinforcement } from '../hooks/useMemoryReinforcement';
import { MemoryReinforcementToast } from '../components/MemoryReinforcementToast';
import { ICON_COMPONENTS, COLOR_HEX } from '../lib/categoryIcons';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// Same cutoff InsightsService.MOVER_SIGNIFICANCE_THRESHOLD_PCT already uses server-side to decide
// which movers are worth a sentence -- reused here rather than a separate frontend-only number, so
// "this mover gets a badge" and "this mover gets a sentence" agree on what counts as significant.
const MOVER_SIGNIFICANCE_THRESHOLD_PCT = 15;

/** Matches the observation blocks' real shape: full-width padded boxes, not text lines. */
function ObservationsSkeleton() {
  return (
    <div className="space-y-3">
      {[0, 1, 2].map((i) => (
        <Skeleton.Block key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}

/**
 * The row shape both list cards share: a label on the left, figures on the right, on a dashed
 * divider -- matching the real `flex justify-between ... border-b border-dashed py-2` rows rather
 * than using Skeleton.Row, whose fixed field-and-button composition belongs to a different shape
 * (Ledger hand-composed its rows for the same reason).
 */
function ListSkeleton({ rows }: { rows: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex justify-between items-center border-b border-dashed py-2">
          <Skeleton.Text width="w-1/3" />
          <Skeleton.Text width="w-1/4" className="h-2.5" />
        </div>
      ))}
    </div>
  );
}

/** The 32px rounded-icon-on-tinted-background chip Budgets.tsx's own category rows already use --
 *  applied here to Category Movers, Recurring, and the Top Merchant row for visual consistency
 *  with the rest of the app's redesigned list rows, instead of this page's plain text-only lines. */
function CategoryIcon({ icon: Icon, color }: { icon: LucideIcon; color: string }) {
  return (
    <div
      className="w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0"
      style={{ backgroundColor: `${color}26` }}
    >
      <Icon size={16} style={{ color }} />
    </div>
  );
}

export default function Insights() {
  const [data, setData] = useState<InsightsData | null>(null);
  const [recurring, setRecurring] = useState<RecurringItem[]>([]);
  // No loading/error flags, deliberately: this is Fyn's optional gloss on data the Observations
  // card above already shows numerically (rule-based, not an LLM call). A 403 (not entitled), 503
  // (Fyn disabled/over budget), 404 (nothing to narrate yet), or an Anthropic outage should all
  // just mean this section never appears -- not a skeleton, not an error message, nothing the user
  // would read as something being broken. See insightsApi.narration's own doc.
  const [narration, setNarration] = useState<string | null>(null);
  // Found in review: unlike every other call in the effect below, narration() spends real
  // Anthropic API cost per call. React.StrictMode (see main.tsx) double-invokes effects in
  // development -- harmless for the free get()/list() calls beside it, but silently doubles real
  // spend for this one specifically. This ref persists across StrictMode's mount-cleanup-remount
  // cycle (same component instance), so it survives to block the second invocation.
  const narrationRequested = useRef(false);
  // Two endpoints, two sets of flags. These used to share one `loading` and one `error` behind a
  // single Promise.all, which conflated sources that have no dependency on each other: /recurring
  // feeds only the Recurring card, /insights only the Observations and Movers cards. That shared
  // gate meant the whole page waited on the slower of the two, and -- worse -- a /recurring failure
  // blanked Observations and Movers even though /insights had succeeded. Splitting them is what
  // the roadmap's "section-scoped loading, not page-scoped, whenever sections are independently
  // sourced" rule requires (§1), not incidental refactoring.
  const [insightsLoading, setInsightsLoading] = useState(true);
  const [recurringLoading, setRecurringLoading] = useState(true);
  const [insightsError, setInsightsError] = useState(false);
  const [recurringError, setRecurringError] = useState(false);
  const [checklist, setChecklist] = useState<ChecklistStatus | null>(null);
  // Cosmetic only, same "safe fallback, never blocks the page" reasoning Budgets.tsx's own
  // categoriesApi.list() call documents -- a failure here just leaves every icon/color lookup
  // below on its already-built 'tag'/'gray' fallback, nothing more.
  const [categoriesByName, setCategoriesByName] = useState<Map<string, CategoryOption>>(new Map());
  // Fyn's narration, once it succeeds, REPLACES the rule-based sentence list as the primary text
  // (rather than sitting as a gloss line above it) -- the repo owner's explicit call: insights
  // should read as AI-composed prose, not Java template strings, whenever Fyn is available. The
  // underlying rule-based sentences stay one click away for anyone who wants to see the exact
  // numbers Fyn was given, rather than disappearing.
  const [showNumbers, setShowNumbers] = useState(false);
  const queryClient = useQueryClient();

  // Getting-started checklist: "View insights" fires once, on a 1.5s dwell rather than on mount
  // itself, so a user who opens this page and immediately navigates away doesn't get credited for
  // a screen they never actually looked at.
  useEffect(() => {
    onboardingApi.getChecklist().then(setChecklist).catch(() => {});
  }, []);
  useEffect(() => {
    const item = checklist?.items.find((i) => i.key === 'VIEW_INSIGHTS');
    if (!item || item.completed) return;
    const timer = setTimeout(() => {
      // Bug fix: this used to leave the shared ['onboarding'] react-query cache untouched after a
      // successful completion -- this page tracks its own `checklist` via local state, not that
      // cache, so nothing here needed it. But Dashboard's ChecklistWidget reads the SAME backend
      // state through that cache key with a 30s staleTime, so without invalidating it here, it
      // could keep showing "View insights" as unchecked for up to 30s after it was actually
      // completed. Same fix as Ledger.tsx's own REVIEW_TRANSACTIONS dwell timer.
      onboardingApi.completeChecklistItem('VIEW_INSIGHTS')
        .then(() => queryClient.invalidateQueries({ queryKey: ['onboarding'] }))
        .catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, [checklist, queryClient]);

  // Real usage tracking for Billing.tsx's "Smart Insights" tile -- same 1.5s dwell convention as
  // the checklist timer above (a bounced visit shouldn't count as a view), but fires on every
  // mount rather than once ever: this is a running count, not a getting-started checklist item.
  useEffect(() => {
    const timer = setTimeout(() => {
      usageApi.recordView('insights').catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    insightsApi.get()
      .then(setData)
      .catch(() => setInsightsError(true))
      .finally(() => setInsightsLoading(false));
    recurringApi.list()
      .then(setRecurring)
      .catch(() => setRecurringError(true))
      .finally(() => setRecurringLoading(false));
    if (!narrationRequested.current) {
      narrationRequested.current = true;
      insightsApi.narration().then(setNarration).catch(() => {});
    }
  }, []);

  useEffect(() => {
    categoriesApi.list()
      .then((cats) => setCategoriesByName(new Map(cats.map((c) => [c.name, c]))))
      .catch(() => {});
  }, []);

  const showInsightsSkeleton = useDelayedLoading(insightsLoading);
  const showRecurringSkeleton = useDelayedLoading(recurringLoading);

  function iconFor(categoryName: string): LucideIcon {
    return ICON_COMPONENTS[categoriesByName.get(categoryName)?.icon ?? 'tag'] ?? ICON_COMPONENTS.tag;
  }
  function colorFor(categoryName: string): string {
    return COLOR_HEX[categoriesByName.get(categoryName)?.color ?? 'gray'];
  }

  // Optimistic: this list is purely informational, so there is no real cost to a rare rollback
  // flashing the row back in on a failed request. Invalidates the shared ['recurring'] react-query
  // cache key too -- this page tracks its own `recurring` local state rather than that cache (see
  // the loading-state split's own comment above), but Dashboard.tsx's widget reads the SAME
  // backend list through it, and would otherwise keep showing an already-dismissed row until its
  // own cache happened to go stale.
  function dismissRecurring(merchant: string) {
    const previous = recurring;
    setRecurring((items) => items.filter((item) => item.merchant !== merchant));
    recurringApi.dismiss(merchant)
      .then(() => queryClient.invalidateQueries({ queryKey: ['recurring'] }))
      .catch(() => setRecurring(previous));
  }

  // Issue #1451: "confirm" has no persisted state of its own (see backend RecurringService
  // .confirm's own doc comment) -- not being dismissed already keeps a group showing on every
  // future GET. confirmedMerchants is purely local UI state, so the button doesn't invite firing
  // the same reinforcement copy twice in a row for the same row.
  const [confirmedMerchants, setConfirmedMerchants] = useState<Set<string>>(new Set());
  const memoryReinforcement = useMemoryReinforcement();
  function confirmRecurring(merchant: string) {
    recurringApi.confirm(merchant).then(() => {
      setConfirmedMerchants((prev) => new Set(prev).add(merchant));
      memoryReinforcement.show("Fynora will remember this — we'll keep tracking it as recurring.");
    }).catch(() => {});
  }

  // `data` staying null on failure used to fall through to `return null`, rendering a blank page
  // with no indication anything went wrong. That message now lives per-card below rather than as a
  // page-level early return, so one failed endpoint no longer takes the other's card down with it.
  const insightsFailed = insightsError || !data;
  const movers = (data?.movers ?? []).filter((m) => m.pctChange !== null).slice(0, 6);
  const hasNarration = !!narration;

  return (
    <div className="space-y-6">
      <div className="bg-primary/10 border-l-4 border-primary rounded p-3 text-sm">
        These are rule-based statistical observations, computed directly from your real transaction history.
      </div>

      <FinoraCard>
        <SectionHeader title="Key Insights" />
        {insightsLoading ? (
          // Region outside the delayed gate, shapes inside -- the accessible label announces
          // immediately while only the visual shape waits out the anti-flash window
          // (ChartContainer.tsx is the reference implementation of this contract).
          <Skeleton.Region label="Loading this month's observations">
            {showInsightsSkeleton && <ObservationsSkeleton />}
          </Skeleton.Region>
        ) : insightsFailed ? (
          <p className="text-muted text-sm">Couldn't load your insights — please try again later.</p>
        ) : (
          <div className="space-y-3">
            {hasNarration ? (
              <div className="space-y-2">
                <p className="text-sm leading-relaxed border-l-4 border-accent bg-accent/5 rounded p-3">
                  <span className="font-medium text-accent">Fyn: </span>{narration}
                </p>
                <button
                  type="button"
                  onClick={() => setShowNumbers((v) => !v)}
                  aria-expanded={showNumbers}
                  className="text-xs font-medium text-primary underline underline-offset-2"
                >
                  {showNumbers ? 'Hide the numbers' : 'View the numbers'}
                </button>
                {showNumbers && (
                  <div className="space-y-2 pt-1">
                    {data!.sentences.map((s, i) => (
                      <p key={i} className="text-sm leading-relaxed border-l-4 border-border bg-black/[0.02] rounded p-3">{s}</p>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              // Fyn's narration is still loading, unavailable (not entitled, over budget, an
              // Anthropic outage), or genuinely had nothing to say -- the same rule-based sentences
              // this page has always shown, uncollapsed, so nothing regresses when AI isn't in the
              // loop for this request.
              data!.sentences.map((s, i) => (
                <p key={i} className="text-sm leading-relaxed border-l-4 border-border bg-black/[0.02] rounded p-3">{s}</p>
              ))
            )}

            {data!.topMerchant && (
              // Deliberately never narrated by Fyn -- FynInsightsNarrationService's own doc
              // comment excludes merchant names from what reaches the LLM (Tier 1/2 data
              // boundary), so this is the one insight that always needs an explicit home here
              // rather than folding into the AI prose above.
              <div className="flex items-center gap-3 border-l-4 border-border bg-black/[0.02] rounded p-3">
                <CategoryIcon icon={Trophy} color={COLOR_HEX.gray} />
                <p className="text-sm leading-relaxed">
                  Your top merchant this month was{' '}
                  <span className="font-semibold">"{data!.topMerchant.name}"</span> at{' '}
                  <span className="font-semibold">{fmt(data!.topMerchant.amount)}</span>.
                </p>
              </div>
            )}
          </div>
        )}
      </FinoraCard>

      <FinoraCard>
        <SectionHeader title="Recurring Payments & Subscriptions" />
        {recurringLoading ? (
          <Skeleton.Region label="Loading recurring payments">
            {showRecurringSkeleton && <ListSkeleton rows={3} />}
          </Skeleton.Region>
        ) : recurringError ? (
          <p className="text-muted text-sm">Couldn't load your recurring payments — please try again later.</p>
        ) : recurring.length === 0 ? (
          <EmptyState
            icon={Repeat}
            iconBg="bg-primary-light"
            iconColor="text-primary"
            title="No recurring payments detected yet"
            desc="This needs at least 2 charges from the same merchant with a regular interval to spot a pattern."
          />
        ) : (
          <div className="space-y-2">
            {recurring.map((r) => (
              <div key={r.merchant} className="flex items-center gap-3 text-sm border-b border-dashed py-2">
                <CategoryIcon icon={Repeat} color={COLOR_HEX.blue} />
                <span className="flex-1 min-w-0 capitalize truncate">{r.merchant} <Badge label={r.label} className="ml-1" /></span>
                <span className="flex items-center gap-3 text-xs text-muted flex-shrink-0">
                  <span>{fmt(r.averageAmount)} · {r.occurrences}x seen</span>
                  <span>next ~{r.nextEstimate}</span>
                  <button
                    type="button"
                    onClick={() => confirmRecurring(r.merchant)}
                    disabled={confirmedMerchants.has(r.merchant)}
                    aria-label={confirmedMerchants.has(r.merchant) ? `${r.merchant} confirmed as recurring` : `Confirm ${r.merchant} as recurring`}
                    title={confirmedMerchants.has(r.merchant) ? 'Confirmed' : 'Yes, keep tracking this'}
                    className="text-muted hover:text-success disabled:hover:text-muted disabled:opacity-50"
                  >
                    <Check size={13} className={confirmedMerchants.has(r.merchant) ? 'text-success' : undefined} />
                  </button>
                  <button
                    type="button"
                    onClick={() => dismissRecurring(r.merchant)}
                    aria-label={`Not recurring: dismiss ${r.merchant}`}
                    title="Not recurring"
                    className="text-muted hover:text-ink"
                  >
                    <X size={13} />
                  </button>
                </span>
              </div>
            ))}
          </div>
        )}
      </FinoraCard>

      <FinoraCard>
        <SectionHeader title="Category Movers vs. Recent Average" />
        {insightsLoading ? (
          <Skeleton.Region label="Loading category movers">
            {showInsightsSkeleton && <ListSkeleton rows={4} />}
          </Skeleton.Region>
        ) : insightsFailed ? (
          <p className="text-muted text-sm">Couldn't load your insights — please try again later.</p>
        ) : movers.length === 0 ? (
          <EmptyState
            icon={TrendingUp}
            iconBg="bg-accent-purple-bg"
            iconColor="text-accent-purple"
            title="Not enough history yet"
            desc="Add a few months of transactions to compare trends."
          />
        ) : (
          <div className="space-y-2">
            {movers.map((m) => {
              const isSignificant = Math.abs(m.pctChange!) >= MOVER_SIGNIFICANCE_THRESHOLD_PCT;
              const rising = m.pctChange! >= 0;
              return (
                <div key={m.category} className="flex items-center gap-3 text-sm border-b border-dashed py-2">
                  <CategoryIcon icon={iconFor(m.category)} color={colorFor(m.category)} />
                  <span className="flex items-center gap-2 flex-1 min-w-0">
                    <span className="truncate">{m.category}</span>
                    {isSignificant && <Badge tone={rising ? 'danger' : 'success'} label={rising ? 'Up' : 'Down'} />}
                  </span>
                  <span className="flex items-center gap-3 flex-shrink-0">
                    <span className="text-muted text-xs">{fmt(m.current)} vs usual {fmt(m.priorAverage)}</span>
                    <span className={rising ? 'text-danger' : 'text-success'}>
                      {rising ? '▲' : '▼'} {Math.abs(m.pctChange!).toFixed(0)}%
                    </span>
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </FinoraCard>

      <MemoryReinforcementToast message={memoryReinforcement.message} />
    </div>
  );
}
