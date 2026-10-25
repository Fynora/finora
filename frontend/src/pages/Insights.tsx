import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Trophy } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import {
  accountsApi, aiInsightsApi, insightsApi, recurringApi, onboardingApi, reportsApi, usageApi,
  type AiStory, type InsightAcknowledgement, type InsightsData, type RecurringItem, type ChecklistStatus,
} from '../api/endpoints';
import { AiText } from '../components/insights/AiText';
import { LookAtCard } from '../components/insights/LookAtCard';
import { ComingUpCard, PaidRegularlyCard } from '../components/insights/RegularPaymentsCards';
import { FinoraCard, Skeleton } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';
import { useMemoryReinforcement } from '../hooks/useMemoryReinforcement';
import { MemoryReinforcementToast } from '../components/MemoryReinforcementToast';
import { WithPeopleCard } from '../components/people/WithPeopleCard';
import { COLOR_HEX } from '../lib/categoryIcons';
import {
  LOOK_AT_KEY, ackEntry, comingUp, isAcked, lookAtRowsFromServer, monthLine, paidRegularly, rowAck, type LookAtRow,
} from '../lib/insightsFeed';
import { trackNavigation } from '../lib/trackNavigation';
import { toLocalDateString } from '../utils/date';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// While another request is writing the story, read it again this many times, this far apart
// (the same as mobile's Insights screen).
const STORY_POLLS = 2;
const STORY_POLL_MS = 2000;

/** "2026-06" -> "June 2026" -- same construction as Reports.tsx's own monthLabel. */
function monthLabelLong(monthStr: string) {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}

/**
 * "this month" / "in June 2026" for the month an insights response describes. Statements are
 * imported in arrears, so InsightsService's month (newest with a purchase) is routinely not the
 * current one; the response says which it is. No month means nothing to report on, which reads as
 * current (ReportingPeriod's no-data rule); a month without an explicit reportingMonthIsCurrent is
 * named rather than assumed current.
 */
function insightsPeriodPhrase(data: InsightsData | null | undefined) {
  const reportingMonth = data?.reportingMonth ?? null;
  const reportingMonthIsCurrent = !reportingMonth || data?.reportingMonthIsCurrent === true;
  return reportingMonthIsCurrent ? 'this month' : `in ${monthLabelLong(reportingMonth!)}`;
}

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

/** The 32px rounded-icon-on-tinted-background chip Budgets.tsx's own category rows already use. */
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

function queryStatus(q: { isLoading: boolean; isError: boolean; data: unknown }): 'loading' | 'error' | 'ready' {
  if (q.isLoading) return 'loading';
  if (q.isError && q.data === undefined) return 'error';
  return 'ready';
}

/**
 * Insights on the website (docs/superpowers/specs/2026-10-24-web-insights-redesign-design.md, the
 * website form of the mobile Insights redesign): what's worth knowing about the newest month. On a
 * wide screen the left column is what to act on (the month, the AI story, "To look at") and the right
 * what to glance at (coming up, paid regularly, with people); below `lg` it is one column, with
 * question links that jump down it.
 */
export default function Insights() {
  const [data, setData] = useState<InsightsData | null>(null);
  // The AI month story (AI insights spec §8.4). No loading or error flags, deliberately: until an AI
  // story is ready, and whenever there is none (not in the audience, a spent budget, a failed check,
  // an outage), the card shows its own rule-based sentences -- no skeleton, no message, nothing that
  // reads as broken. Rules text from the server is not shown: the sentences already say it.
  const [story, setStory] = useState<AiStory | null>(null);
  // Writing a story spends real model cost. React.StrictMode (see main.tsx) runs effects twice in
  // development; this ref persists across StrictMode's mount-cleanup-remount cycle (same component
  // instance), so the second run reads and writes nothing.
  const storyRequested = useRef(false);
  // A poll must not outlive the page. Its own effect, so StrictMode's cleanup-remount sets it back.
  const mounted = useRef(true);
  const storyPoll = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Which story load is the newest; an older one's answers are dropped.
  const storyLoad = useRef(0);
  // Section-scoped loading (§1): /insights feeds the month card only, so a slow or failed /recurring
  // never holds it back, and the other way round.
  const [insightsLoading, setInsightsLoading] = useState(true);
  const [insightsError, setInsightsError] = useState(false);
  const [checklist, setChecklist] = useState<ChecklistStatus | null>(null);
  // An AI story, once ready, REPLACES the rule-based sentence list as the primary text (rather than
  // sitting as a gloss line above it) -- the repo owner's explicit call: insights should read as
  // prose, not template strings, whenever the AI story is available. The rule-based sentences stay
  // one click away for anyone who wants the exact figures, rather than disappearing.
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
      // Dashboard's ChecklistWidget reads the same state through the ['onboarding'] cache.
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

  const recurringQ = useQuery({ queryKey: ['recurring'], queryFn: () => recurringApi.list() });
  const lookAtQ = useQuery({ queryKey: LOOK_AT_KEY, queryFn: () => aiInsightsApi.lookAt(), retry: false });
  const acksQ = useQuery({ queryKey: ['insight-acknowledgements'], queryFn: () => insightsApi.acknowledgements(), retry: false });
  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths(), retry: false });
  const accountsQ = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list(), retry: false });
  const reportingMonth = data?.reportingMonth ?? null;
  // Reports' own trend, so the month line and Reports can never disagree.
  const trendQ = useQuery({
    queryKey: ['report', 'trend', reportingMonth],
    queryFn: () => reportsApi.trend(reportingMonth as string, 6),
    enabled: reportingMonth !== null,
    retry: false,
  });

  // A share, a loan, a rule or a repeating-payment answer (which re-files past payments) moves the
  // month's figures, which this page keeps in local state: re-read them and the story written from
  // them. The cached lists (repeating payments, the trend, "To look at") are already refreshed by the
  // answer itself (useInvalidateMoneyFigures).
  const reloadAfterAnswer = () => {
    insightsApi.get().then(setData).catch(() => {});
    loadStory();
  };

  function loadStory() {
    const load = ++storyLoad.current;
    if (storyPoll.current) clearTimeout(storyPoll.current);
    let polls = 0;
    const current = () => mounted.current && load === storyLoad.current;
    const take = (s: AiStory) => {
      if (!current()) return;
      setStory(s);
      if (s.status === 'generating' && polls < STORY_POLLS) {
        polls += 1;
        storyPoll.current = setTimeout(() => {
          aiInsightsApi.story().then(take).catch(() => {});
        }, STORY_POLL_MS);
      }
    };
    aiInsightsApi.story()
      .then((s) => {
        if (!current()) return;
        take(s);
        // Never a second write from this load, whatever the polls answer.
        if (s.canGenerate && s.status !== 'generating') {
          aiInsightsApi.generateStory().then(take).catch(() => {});
        }
      })
      .catch(() => {});
  }

  useEffect(() => {
    insightsApi.get()
      .then(setData)
      .catch(() => setInsightsError(true))
      .finally(() => setInsightsLoading(false));
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (storyPoll.current) clearTimeout(storyPoll.current);
    };
  }, []);

  // The story for the newest reporting month (no month sent), the month get() above describes. Each
  // load reads it once and, when the read says a story may be written (canGenerate -- none yet, or
  // the one shown was written for older figures), asks the server to write it once; while another
  // request is writing it, it reads again a couple of times. The page shows what the newest answer
  // says: an AI story, or nothing. A load started later wins over one still answering.
  useEffect(() => {
    if (storyRequested.current) return;
    storyRequested.current = true;
    loadStory();
  }, []);

  const showInsightsSkeleton = useDelayedLoading(insightsLoading);

  const recurring = useMemo(() => recurringQ.data ?? [], [recurringQ.data]);
  const acks = useMemo(() => new Set((acksQ.data ?? []).map((a) => ackEntry(a.kind, a.itemKey))), [acksQ.data]);
  const lookAtRows = useMemo(
    () => (lookAtQ.data ? lookAtRowsFromServer(lookAtQ.data.rows).filter((r) => !isAcked(r, acks)) : []),
    [lookAtQ.data, acks],
  );
  const upcoming = useMemo(() => comingUp(recurring, toLocalDateString(new Date())), [recurring]);
  const regular = useMemo(() => paidRegularly(recurring), [recurring]);
  const latestStatementEnd = useMemo(() => {
    const ends = (accountsQ.data ?? [])
      .map((a) => a.lastStatementPeriodEnd)
      .filter((d): d is string => typeof d === 'string' && d.length > 0)
      .sort();
    return ends.length > 0 ? ends[ends.length - 1] : null;
  }, [accountsQ.data]);
  const line = monthLine(reportingMonth, trendQ.data ?? []);
  // One month of data or none: nothing to compare yet.
  const firstMonth = monthsQ.data !== undefined && monthsQ.data.length <= 1;
  const recurringStatus = queryStatus(recurringQ);

  // Saved through the cache, not a mutation hook: LookAtCard saves what is still waiting when the page
  // closes, after this component's own hooks are gone.
  function acknowledge(row: LookAtRow) {
    const ack = rowAck(row);
    if (!ack) return;
    const entry: InsightAcknowledgement = { kind: ack.kind, itemKey: ack.itemKey };
    queryClient.setQueryData<InsightAcknowledgement[]>(['insight-acknowledgements'], (list) => [...(list ?? []), entry]);
    insightsApi.acknowledge(ack.kind, ack.itemKey)
      .then(() => queryClient.invalidateQueries({ queryKey: ['ai-insights', 'look-at'] }))
      .catch(() => {
        // Not saved: the row comes back, as the server still has it.
        queryClient.setQueryData<InsightAcknowledgement[]>(['insight-acknowledgements'], (list) =>
          (list ?? []).filter((a) => !(a.kind === entry.kind && a.itemKey === entry.itemKey)));
      });
  }

  // Optimistic: this list is purely informational, so there is no real cost to a rare rollback
  // flashing the row back in on a failed request. Dashboard reads the same ['recurring'] cache.
  function dismissRecurring(merchant: string) {
    const previous = queryClient.getQueryData<RecurringItem[]>(['recurring']);
    queryClient.setQueryData<RecurringItem[]>(['recurring'], (items) => (items ?? []).filter((item) => item.merchant !== merchant));
    recurringApi.dismiss(merchant)
      .then(() => queryClient.invalidateQueries({ queryKey: ['recurring'] }))
      .catch(() => queryClient.setQueryData(['recurring'], previous));
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
  // with no indication anything went wrong. That message lives in the month card, so one failed
  // endpoint no longer takes the other sections down with it.
  const insightsFailed = insightsError || !data;
  // Only AI text leads the card; rules text and "none" leave the sentences in place.
  const aiStory = story && story.writtenByAi && story.text ? story : null;
  const reportsLink = reportingMonth ? `/app/reports?month=${reportingMonth}` : '/app/reports';

  // Narrow screens only: one long column, so questions that jump down it (mobile spec idea 4).
  const questions = [
    lookAtRows.length > 0 && { href: '#look-at', text: `What needs a look? (${lookAtRows.length})` },
    upcoming.rows.length > 0 && { href: '#coming-up', text: "What's coming up?" },
    regular.rows.length > 0 && { href: '#paid-regularly', text: 'What do I pay regularly?' },
  ].filter((q): q is { href: string; text: string } => Boolean(q));

  return (
    <div className="space-y-4">
      <div className="grid gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] items-start">
        <div className="space-y-4 min-w-0">
          <FinoraCard>
            {insightsLoading ? (
              // Region outside the delayed gate, shapes inside -- the accessible label announces
              // immediately while only the visual shape waits out the anti-flash window.
              <Skeleton.Region label="Loading your observations">
                {showInsightsSkeleton && <ObservationsSkeleton />}
              </Skeleton.Region>
            ) : insightsFailed ? (
              <p className="text-muted text-sm">Couldn't load your insights — please try again later.</p>
            ) : (
              <div className="space-y-3">
                {reportingMonth && (
                  <div>
                    <h2 className="text-xs text-muted">{monthLabelLong(reportingMonth)}</h2>
                    {line && <p className="text-base text-ink">{line}</p>}
                  </div>
                )}
                {aiStory ? (
                  <div className="space-y-2">
                    <div className="border-l-4 border-accent-purple bg-accent-purple-bg rounded p-3">
                      <AiText
                        key={aiStory.id ?? 'story'}
                        id={aiStory.id}
                        text={aiStory.text!}
                        writtenByAi
                        basis={aiStory.basis}
                        rating={aiStory.rating}
                        ratingReason={aiStory.ratingReason}
                        onFeedback={(rating, ratingReason) => setStory((s) => (s ? { ...s, rating, ratingReason } : s))}
                      />
                    </div>
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
                  // The AI story is still loading, unavailable, or there is nothing to say: the same
                  // rule-based sentences this page has always shown, uncollapsed.
                  data!.sentences.map((s, i) => (
                    <p key={i} className="text-sm leading-relaxed border-l-4 border-border bg-black/[0.02] rounded p-3">{s}</p>
                  ))
                )}

                {data!.topMerchant && (
                  // Never in the AI story -- no payee name reaches the model (AI insights spec §4.1).
                  <div className="flex items-center gap-3 border-l-4 border-border bg-black/[0.02] rounded p-3">
                    <CategoryIcon icon={Trophy} color={COLOR_HEX.gray} />
                    <p className="text-sm leading-relaxed">
                      Your top merchant {insightsPeriodPhrase(data)} was{' '}
                      <span className="font-semibold">"{data!.topMerchant.name}"</span> at{' '}
                      <span className="font-semibold">{fmt(data!.topMerchant.amount)}</span>.
                    </p>
                  </div>
                )}
              </div>
            )}
            {questions.length > 0 && (
              <nav aria-label="Jump to" className="lg:hidden flex flex-wrap gap-2 mt-3">
                {questions.map((q) => (
                  <a key={q.href} href={q.href} className="text-xs border border-border rounded-full px-3 py-1 hover:bg-black/[0.03]">
                    {q.text}
                  </a>
                ))}
                <Link
                  to={reportsLink}
                  onClick={() => trackNavigation('reports', 'contextual')}
                  className="text-xs border border-border rounded-full px-3 py-1 hover:bg-black/[0.03]"
                >
                  Where did my money go?
                </Link>
              </nav>
            )}
          </FinoraCard>

          <LookAtCard
            status={queryStatus(lookAtQ)}
            rows={lookAtRows}
            month={reportingMonth}
            firstMonth={firstMonth}
            onAck={acknowledge}
            onRetry={() => void lookAtQ.refetch()}
            onAnswered={reloadAfterAnswer}
          />
        </div>

        <div className="space-y-4 min-w-0">
          <div id="coming-up">
            <ComingUpCard
              rows={upcoming.rows} stillToGoOut={upcoming.stillToGoOut} latestStatementEnd={latestStatementEnd}
              status={recurringStatus}
            />
          </div>
          <div id="paid-regularly">
            <PaidRegularlyCard
              rows={regular.rows} monthlyTotal={regular.monthlyTotal} yearlyTotal={regular.yearlyTotal}
              status={recurringStatus} confirmed={confirmedMerchants}
              onConfirm={confirmRecurring} onDismiss={dismissRecurring}
            />
          </div>
          <WithPeopleCard />
        </div>
      </div>

      <footer className="text-center text-xs text-muted space-y-1 pt-2">
        <p>
          <Link to={reportsLink} onClick={() => trackNavigation('reports', 'contextual')} className="text-primary underline underline-offset-2">
            See the full month in Reports
          </Link>
        </p>
        <p>
          {aiStory
            ? 'Text marked "Written by AI" is written by AI from your own figures. Everything else here is rule-based, from your own transaction history.'
            : 'These are rule-based statistical observations, computed directly from your real transaction history.'}
        </p>
      </footer>

      <MemoryReinforcementToast message={memoryReinforcement.message} />
    </div>
  );
}
