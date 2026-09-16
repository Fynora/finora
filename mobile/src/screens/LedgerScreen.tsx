import { useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, FlatList, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRoute, type RouteProp } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import {
  categoriesApi, dashboardApi, onboardingApi, transactionsApi, type PagedResponse, type TransactionFilters,
} from '../api/endpoints';
import { AnimatedNumber } from '../components/AnimatedNumber';
import { BankCorrectionModal } from '../components/BankCorrectionModal';
import { DateField } from '../components/DateField';
import { MarkTransferModal } from '../components/MarkTransferModal';
import { MerchantLogo } from '../components/MerchantLogo';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TransactionExplanationModal } from '../components/TransactionExplanationModal';
import { TransactionSourceModal } from '../components/TransactionSourceModal';
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { AddTransactionSheet } from './AddTransactionSheet';
import { EditTransactionSheet } from './EditTransactionSheet';
import { TransactionDetailSheet } from './TransactionDetailSheet';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { toUserMessage } from '../lib/apiError';
import { hapticError, hapticImpact, hapticSuccess } from '../lib/haptics';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { fmtCurrency, fromLocalDateString, toLocalDateString } from '../lib/format';
import type { KpiItem } from '../components/dashboard/LedgerSnapshotCard';
import { counterpartyLabel } from '../lib/counterpartyLabel';
import { reconciliationBadge } from '../lib/reconciliationBadge';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList, LedgerDrillThroughFilters } from '../navigation/types';
import type { ReconciliationStatus, Transaction } from '../types';

export const LEDGER_PAGE_SIZE = 20;
type TypeFilter = 'ALL' | 'INCOME' | 'EXPENSE';
type StatusFilter = 'ALL' | ReconciliationStatus;
// One entry per real status, in the order they're offered as filter chips. Excludes 'OK' from the
// non-ALL set deliberately: reconciliationBadge already returns null for it (nothing to badge or
// explain), but "show me only the ordinary, unflagged rows" is still a real filter someone
// reviewing a batch of flagged rows might want -- so 'OK' gets its own chip below, worded
// separately from the reconciliationBadge-derived labels the rest reuse.
const STATUS_FILTERS: ReconciliationStatus[] = [
  'DUPLICATE', 'TRANSFER', 'REFUND', 'REVERSAL', 'INVESTMENT_TRANSFER', 'SUPERSEDED',
];

/**
 * The exact filters this screen's own useInfiniteQuery below sends on a fresh mount (no search
 * keyword typed, no type filter chosen). Exported so Dashboard's prefetch-on-focus hook
 * (usePrefetchAdjacentScreens) can warm ['transactions', DEFAULT_LEDGER_FILTERS] under this EXACT
 * key -- a prefetch built from a near-identical object is a cache miss with extra network calls,
 * not a warm cache.
 */
export const DEFAULT_LEDGER_FILTERS: TransactionFilters = {
  size: LEDGER_PAGE_SIZE,
  sortField: 'date',
  sortDir: 'desc',
};

/** Exported for the same reason as DEFAULT_LEDGER_FILTERS -- prefetchInfiniteQuery needs the
 *  identical pagination cursor logic this screen's own useInfiniteQuery uses below, so a
 *  prefetched page and a screen-fetched page agree on whether there's a next one. */
export function getLedgerNextPageParam(lastPage: PagedResponse<Transaction>) {
  return lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined;
}

/**
 * Phase 5 (Low-Priority Polish). Mobile equivalent of the web's identical statusBadges
 * (frontend/src/pages/Ledger.tsx) -- see that function's own doc comment: `needsCategoryReview`
 * and `recurring` are independent booleans, both worth showing at once (a recurring subscription
 * that also needs a category review is real, and a reader shouldn't lose the "this repeats"
 * signal just because the row also needs review). Reviewed/Categorized only fills in when
 * NEITHER of those is true -- the "nothing else to say" fallback, not one more option in a chain.
 * Every field this reads (needsCategoryReview, recurring, categoryManuallySet) already exists on
 * Transaction; this was never fetched-but-unrendered so much as never rendered at all on mobile.
 *
 * <p>Plan 6, Track B mobile parity added `pendingBankCorrection` here too -- unlike web (where
 * this badge is its own clickable button, since web's badges ARE buttons), mobile's badges are
 * all plain, non-interactive Text pills; the interactive path here is a dedicated icon button on
 * the row (mirrors source/edit/explain), not the badge itself. Folding it into this same function
 * keeps it visually consistent with every other review-state label a row can carry.
 */
export function statusBadges(t: Transaction): { label: string; tone: 'warning' | 'primary' | 'success' | 'danger' }[] {
  const badges: { label: string; tone: 'warning' | 'primary' | 'success' | 'danger' }[] = [];
  if (t.pendingBankCorrection) badges.push({ label: 'Bank Correction', tone: 'danger' });
  if (t.needsCategoryReview) badges.push({ label: 'Needs Review', tone: 'warning' });
  if (t.recurring) badges.push({ label: 'Recurring', tone: 'primary' });
  if (badges.length === 0) {
    badges.push(t.categoryManuallySet ? { label: 'Reviewed', tone: 'primary' } : { label: 'Categorized', tone: 'success' });
  }
  return badges;
}

type GroupedRow =
  | { kind: 'header'; date: string; label: string; subtotal: number }
  | { kind: 'row'; transaction: Transaction };

// Bug fix. DUPLICATE and SUPERSEDED rows are still rendered inline (with their own badge --
// LedgerScreen shows everything, unlike the Dashboard/Reports screens, which is exactly why the
// default statusFilter is 'ALL' and these rows ordinarily sit in `txns` right alongside everything
// else), but they are not a second real transaction: DUPLICATE means the app itself believes this
// is the same purchase recorded twice, and SUPERSEDED means this row is history a later re-upload
// of the exact same statement period replaced. Summing either into the day's subtotal double-counts
// money that never actually moved twice -- the identical defect RefundNetting.reportable() exists
// to keep out of every backend total (Dashboard, Reports), just recreated here in a client-side
// aggregate the backend never computes. TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER stay included
// on purpose -- unlike these two, they represent real money that genuinely moved on this account
// today, the same convention Account.balance itself uses (see RefundNetting's own class doc).
const SUBTOTAL_EXCLUDED_STATUSES: ReadonlySet<ReconciliationStatus> = new Set(['DUPLICATE', 'SUPERSEDED']);

/**
 * Groups a merged list of transactions into day sections with a per-day net subtotal (income
 * minus expense for that day, signed the same way a single row's own amount is -- positive shows
 * in c.success, negative in c.danger, same convention as every other signed figure in this app).
 *
 * Runs on the FULLY MERGED txns array (every fetched page flattened), not per-page -- grouping
 * a day that happens to straddle two 20-row server pages still produces one header for that day,
 * since by the time this runs both pages are already concatenated.
 *
 * Groups by date VALUE (a Map, not "does this row's date differ from the one right before it"),
 * deliberately not assuming same-date rows stay contiguous. The backend's own sort
 * (TransactionService.java's `Sort.by(direction, mapSortField(sortField))`) orders by txnDate
 * alone with no secondary tiebreaker, so two rows sharing a date have no guaranteed relative order
 * across separate page fetches -- a value-keyed group merges any such split back into one header
 * instead of silently rendering the same day twice with two different subtotals.
 *
 * `today` is a parameter, not read internally, so the caller controls how fresh "Today"/
 * "Yesterday" are; see this function's call site for why that matters (a memoized call needs the
 * current date as an explicit dependency, or it goes stale across a long-lived, otherwise-idle
 * mount -- the exact class of bug scripts/check-reporting-period-labels.py exists to catch
 * elsewhere in this app).
 */
export function groupTransactionsByDay(txns: Transaction[], today: Date = new Date()): GroupedRow[] {
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  // toLocalDateString, NOT `.toISOString().slice(0, 10)` -- toISOString converts to UTC, which
  // silently shifts the key by the local UTC offset. In any timezone ahead of UTC (IST included --
  // this app's default locale is en-IN) that made "today" compare as YESTERDAY's date and
  // "yesterday" as TODAY's: a row actually dated today fell through to the full formatted-date
  // branch, and yesterday's row was mislabeled "Today" instead. Caught by this function's own
  // tests once they asserted on `label` against a fixed clock rather than only on date/subtotal.
  const todayKey = toLocalDateString(today);
  const yesterdayKey = toLocalDateString(yesterday);

  function labelFor(dateStr: string): string {
    if (dateStr === todayKey) return 'Today';
    if (dateStr === yesterdayKey) return 'Yesterday';
    const d = fromLocalDateString(dateStr);
    return d.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'short', year: 'numeric' });
  }

  const order: string[] = [];
  const byDate = new Map<string, Transaction[]>();
  for (const t of txns) {
    let bucket = byDate.get(t.date);
    if (!bucket) {
      bucket = [];
      byDate.set(t.date, bucket);
      order.push(t.date);
    }
    bucket.push(t);
  }

  const result: GroupedRow[] = [];
  for (const date of order) {
    const rows = byDate.get(date)!;
    const subtotal = rows.reduce(
      (sum, t) => SUBTOTAL_EXCLUDED_STATUSES.has(t.reconciliationStatus)
        ? sum
        : sum + (t.type === 'INCOME' ? t.amount : -Math.abs(t.amount)),
      0
    );
    result.push({ kind: 'header', date, label: labelFor(date), subtotal });
    for (const t of rows) result.push({ kind: 'row', transaction: t });
  }
  return result;
}

export function LedgerScreen() {
  // D3 (Track D security cleanup). Every row here is a real transaction description and amount --
  // the same screenshot/screen-recording exposure Dashboard, Accounts, and Statement History
  // already guard against, just not yet extended to the Ledger itself.
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const largeText = useLargeFontScale();
  const queryClient = useQueryClient();
  const route = useRoute<RouteProp<AppTabParamList, 'Transactions'>>();
  const [keywordInput, setKeywordInput] = useState('');
  const debouncedKeyword = useDebouncedValue(keywordInput, 300);
  const [typeFilter, setTypeFilter] = useState<TypeFilter>('ALL');
  // Phase 4 -- backs the server's own `status` search param (TransactionController.search),
  // unused by any client until now. 'ALL' means no filter, same convention as typeFilter above.
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  // Phase 5 (Low-Priority Polish). A manual date-range pick, independent of the drill-through's
  // OWN dateFrom/dateTo below -- a drill-through arrives already scoped to a period (e.g. "August
  // 2026" from a chart), while this is the user picking their own range by hand. Wins over the
  // drill-through's dates when set, since it's the more RECENT, more deliberate choice; clearing
  // it (DateField's own "Clear" link) falls back to whatever the drill-through, if any, still says.
  const [manualDateFrom, setManualDateFrom] = useState<string | null>(null);
  const [manualDateTo, setManualDateTo] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [recategorizing, setRecategorizing] = useState<Transaction | null>(null);
  const [editingTransaction, setEditingTransaction] = useState<Transaction | null>(null);
  const [addingTransaction, setAddingTransaction] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Track C/C7's "Where did this number come from?" panel -- the id of the row it's open for,
  // null when closed. A plain id rather than the whole Transaction: the panel fetches its own
  // data keyed by id, same lazy pattern as StatementHistoryScreen's StatementDetailModal.
  const [viewingSourceId, setViewingSourceId] = useState<string | null>(null);
  // Phase 4's "Why this category?" panel -- category travels alongside the id because (unlike
  // viewingSourceId's panel) this one echoes the row's own current category as on-screen context,
  // and the row's already-loaded Transaction is gone from this closure by the time the query
  // resolves if the list refetches in between.
  const [explaining, setExplaining] = useState<{ id: string; category: string } | null>(null);
  // Phase 6. markingTransfer opens the paired-transaction picker; unmarkingId tracks an in-flight
  // unmark for its own row's loading state, same convention as deletingId above.
  const [markingTransfer, setMarkingTransfer] = useState<Transaction | null>(null);
  const [unmarkingId, setUnmarkingId] = useState<string | null>(null);
  // Plan 6, Track B mobile parity. The full Transaction, not just an id, same reason
  // markingTransfer needs it above: BankCorrectionModal's context line echoes the row's own
  // current amount/description.
  const [viewingCorrection, setViewingCorrection] = useState<Transaction | null>(null);
  // Redesign: the row's own tap target. Every action that used to be one of the row's five inline
  // icon buttons (change category, edit, view source, explain, mark/unmark transfer, view
  // correction) now lives inside this sheet instead -- see TransactionDetailSheet.tsx's own doc
  // comment for why (each becomes an independently-accessible row there, instead of a button
  // nested inside the row's own already-accessible Pressable).
  const [viewingDetail, setViewingDetail] = useState<Transaction | null>(null);

  // Getting-started checklist: "Review transactions" fires once, on a 1.5s dwell rather than on
  // mount itself, so a user who opens this tab and immediately switches away doesn't get credited
  // for a screen they never actually looked at.
  const checklistQuery = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });
  useEffect(() => {
    const item = checklistQuery.data?.items.find((i) => i.key === 'REVIEW_TRANSACTIONS');
    if (!item || item.completed) return;
    const timer = setTimeout(() => {
      // Bug fix: this used to leave the ['onboarding'] cache untouched after a successful
      // completion, unlike every other checklist-affecting write (Import/Budgets/Goals via
      // invalidateFinancialData) which invalidates it. DashboardScreen's ChecklistWidget holds its
      // own `useQuery` on the same key with the default staleTime, so without this it could keep
      // showing "Review transactions" as unchecked well after it was actually completed here.
      onboardingApi.completeChecklistItem('REVIEW_TRANSACTIONS')
        .then(() => queryClient.invalidateQueries({ queryKey: ['onboarding'] }))
        .catch(() => {});
    }, 1500);
    return () => clearTimeout(timer);
  }, [checklistQuery.data, queryClient]);

  // Track C/C4. The active drill-through, if any -- a donut legend row, a budget card, an
  // insight/mover row, or a report's category breakdown. Local state, not read from route.params
  // directly, because this tab stays mounted like every other one: without a state copy, tapping
  // "Clear" would have nothing to set to null (params themselves are the caller's, not this
  // screen's, to clear), and the stale params would simply reapply on the next render.
  const [activeDrillThrough, setActiveDrillThrough] = useState<LedgerDrillThroughFilters | null>(null);
  const [consumedNonce, setConsumedNonce] = useState<number | null>(null);
  // A keyword arriving via drill-through (Insights' Top Merchant) must filter immediately, not
  // wait out useDebouncedValue's 300ms delay below -- that delay exists to avoid firing a
  // request per keystroke while a human types, which doesn't apply to a single programmatic set.
  // Without this, the search box would show the new keyword instantly (keywordInput updates
  // synchronously) while the actual results stayed unfiltered for up to 300ms -- "the filter says
  // one thing, the results say another", the exact bug class the drill-through reset just below
  // already exists to prevent for manualDateFrom/manualDateTo. Wins over the debounced value only
  // until the user edits the search box by hand (cleared in the TextInput's own onChangeText),
  // same "wins until superseded" shape as manualDateFrom/manualDateTo.
  const [drillThroughKeyword, setDrillThroughKeyword] = useState<string | null>(null);
  const incomingFilters = route.params?.filters;
  // Adjusted during render, not in an effect -- React's documented pattern for "reset state when
  // an input changes" (same pattern ImportScreen's own reimport arrival uses, for the identical
  // reason: this tab's params outlive a visit, so a second drill-through has to be told apart from
  // the first one still sitting in state, which is exactly what the nonce is for).
  if (incomingFilters && incomingFilters.nonce !== consumedNonce) {
    setConsumedNonce(incomingFilters.nonce);
    setActiveDrillThrough(incomingFilters);
    // Bug fix: a manual date-range pick from an EARLIER visit to this still-mounted tab used to
    // survive a brand new drill-through arriving later, since manualDateFrom/manualDateTo win over
    // activeDrillThrough's own dates unconditionally (see the filters useMemo below). Without this,
    // the "Clear filter: <new drill-through's label>" banner would show the new period while the
    // list itself stayed silently scoped to whatever date range was picked by hand before it --
    // exactly the kind of "the filter says one thing, the results say another" bug this screen's
    // own drill-through banner exists to prevent. A fresh drill-through is a new, more recent,
    // equally deliberate choice than a stale manual pick from a previous, unrelated visit.
    setManualDateFrom(null);
    setManualDateTo(null);
    // A keyword-only drill-through (Insights' Top Merchant) has nothing else to filter by, so it
    // must reach the actual search box -- unlike category/account/date, which activeDrillThrough
    // already carries into `filters` directly. Unconditional, same as manualDateFrom/manualDateTo
    // just above -- a stale keyword left typed (or seeded by an EARLIER keyword drill-through)
    // would otherwise silently AND itself onto a fresh, unrelated category/account drill-through,
    // narrowing the results to something that looks broken (e.g. "Dining" plus a leftover
    // merchant keyword matching almost nothing) instead of showing what was actually asked for.
    setKeywordInput(incomingFilters.keyword ?? '');
    setDrillThroughKeyword(incomingFilters.keyword ?? null);
  }

  // Loaded lazily: only fetched once, cheap, and the picker needs it the instant a row is tapped.
  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000, // the category list barely changes within a session
  });

  // Under Dashboard's own ['dashboard-summary'] key so visiting both tabs in one session costs
  // one network call, not two -- see DashboardScreen.tsx's identical query.
  const { data: summary } = useQuery({
    queryKey: ['dashboard-summary'],
    queryFn: () => dashboardApi.summary(),
  });
  const { snapshotKpis, deltaLabel } = useDashboardKpis(summary);

  // Track C/C4. `categoryId` wins when the caller already had one (a Budget carries its own);
  // otherwise resolved from `categoryName` against the SAME category list this screen already
  // fetches for its own picker above -- see LedgerDrillThroughFilters's own doc comment for why
  // that beats adding a categories query to three more screens. Genuinely unresolvable (a category
  // renamed or deleted since the caller last saw it) degrades to no category filter at all rather
  // than a search that can never match anything -- the date range, if any, still narrows the list.
  const resolvedCategoryId = activeDrillThrough?.categoryId
    ?? categories.find((cat) => cat.name === activeDrillThrough?.categoryName)?.id;

  const filters: TransactionFilters = useMemo(
    () => ({
      ...DEFAULT_LEDGER_FILTERS,
      keyword: drillThroughKeyword ?? (debouncedKeyword || undefined),
      type: typeFilter === 'ALL' ? undefined : typeFilter,
      status: statusFilter === 'ALL' ? undefined : statusFilter,
      // accountId: Track C/C6 (ImportScreen's "View in Ledger") is the only caller that ever sets
      // this -- needs no name resolution, since ImportScreen already has the confirmed account's
      // real id from the confirm response itself.
      accountId: activeDrillThrough?.accountId,
      categoryId: resolvedCategoryId,
      dateFrom: manualDateFrom ?? activeDrillThrough?.dateFrom ?? undefined,
      dateTo: manualDateTo ?? activeDrillThrough?.dateTo ?? undefined,
    }),
    [drillThroughKeyword, debouncedKeyword, typeFilter, statusFilter, resolvedCategoryId, activeDrillThrough, manualDateFrom, manualDateTo]
  );

  /**
   * Infinite scroll rather than the web's Previous/Next pagination -- the plan's recommended
   * mobile adaptation. This also removes a whole class of bug the web version needs an effect to
   * handle: deleting the last row of the last page can leave the web ledger on an out-of-range
   * page with no way back, so it watches the server's totalPages and clamps. Here there is no
   * current page to strand -- an invalidation just refetches from page 0 forward.
   */
  const {
    data, isLoading, isError, isFetching, isFetchingNextPage, hasNextPage, fetchNextPage, refetch,
  } = useInfiniteQuery({
    queryKey: ['transactions', filters],
    queryFn: ({ pageParam }) => transactionsApi.search({ ...filters, page: pageParam }),
    initialPageParam: 0,
    // The backend's PagedResponse carries a real totalPages, so "is there more" is answered by
    // the server rather than inferred from whether a page came back full.
    getNextPageParam: getLedgerNextPageParam,
  });

  // Memoized on `data` itself (stable across renders where the query result hasn't changed),
  // not recomputed fresh -- otherwise `.flatMap` would allocate a new array reference every
  // render regardless of whether the underlying pages changed, which would in turn make
  // `groupedRows` below (memoized on THIS array) recompute every render too, defeating its
  // own memoization.
  const txns = useMemo(() => data?.pages.flatMap((p) => p.content) ?? [], [data]);
  const totalElements = data?.pages[0]?.totalElements ?? 0;
  // Computed fresh every render (cheap: one Date construction) and included below as a memo
  // dependency in its own right, alongside txns. Without it, a mount that goes idle overnight --
  // this tab stays mounted as a bottom-tab screen, and nothing here refetches on app foreground
  // (see queryClient.ts's own comment on why refetchOnWindowFocus is deliberately not reimplemented
  // via AppState) -- would keep showing whichever "Today"/"Yesterday" it computed the last time
  // txns itself changed, silently mislabeling yesterday's rows as today's once the day rolls over.
  // Naming it here means ANY re-render after midnight (a keystroke in search, a filter toggle, an
  // unrelated modal opening) corrects the labels, not only one that also happens to refetch data.
  const todayDateKey = new Date().toDateString();
  // O(n) but there's no reason to redo it on every unrelated re-render when neither txns nor the
  // day itself has changed. `todayDateKey` isn't read inside the callback -- groupTransactionsByDay
  // reads the real clock itself via its own `today` default parameter -- it's listed purely to
  // force a recompute once the day rolls over; eslint's exhaustive-deps can't see that reasoning.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const groupedRows = useMemo(() => groupTransactionsByDay(txns), [txns, todayDateKey]);

  /**
   * Change a transaction's category.
   *
   * This is the half of the correction loop the review queue can't reach. That queue only holds
   * transactions the engine knew it was unsure about (needsCategoryReview); a transaction it
   * categorized *confidently and wrongly* never appears there, so before this the ledger had no
   * way to fix one -- long-press to delete was the only write on the row, which meant the only
   * route to correcting a category was destroying the transaction and re-entering it.
   *
   * PATCH .../category is the same endpoint the review queue uses, so a correction here teaches
   * the merchant map identically and sets categoryManuallySet, which stops any later suggestion
   * layer from silently overwriting the answer.
   */
  async function applyCategory(t: Transaction, categoryName: string) {
    setRecategorizing(null);
    if (categoryName === t.categoryName) return;
    setError(null);
    // Deliberately NOT wrapped in useSingleFlight, unlike the write paths on Goals/Budgets. That
    // guard serializes every call through one ref, which is right for a screen with a single
    // submit button where a second press means "the same save, twice". Here each row is its own
    // action: correcting one transaction while another's request is still in flight would be
    // silently discarded, and on a ledger the natural way to use this is to fix several rows in a
    // row. The double-submit it protects against is a non-event anyway -- PATCH .../category sets
    // an explicit category rather than mutating a running value, so applying the same one twice
    // is indistinguishable from applying it once.
    try {
      await transactionsApi.updateCategory(t.id, categoryName);
      hapticSuccess();
      // Not an optimistic edit: the row stays put and only its label changes, so there is no
      // felt latency to hide -- and a category move shifts spend-by-category, budget progress
      // and insights, none of which this screen can guess correctly on its own.
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not change this category.'));
      hapticError();
    }
  }

  function confirmDelete(t: Transaction) {
    // Before the alert, not after a choice is made -- the same convention iOS's own system apps
    // use for a press that's about to open a destructive confirmation, so the gesture itself
    // feels acknowledged rather than only its eventual outcome.
    hapticImpact();
    // Alert.alert replaces the web's window.confirm(), which doesn't exist in React Native.
    Alert.alert(
      'Delete transaction?',
      `"${t.description || t.merchant}" (${fmtCurrency(t.amount)}) can't be recovered.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            // Deferred until handleDelete SETTLES, not fired the instant Delete is tapped --
            // closing immediately made the sheet vanish in the very same render `deletingId`
            // first becomes true, so its own "Deleting…" row (deleting prop) could never actually
            // be seen: the component showing it would already be unmounted. Closing only once
            // the request settles (success or failure) lets that state be observed, matching the
            // in-flight indicator the row itself has always shown for this same request. Harmless
            // no-op when called from the row's own long-press, where nothing is open.
            void handleDelete(t).finally(() => setViewingDetail(null));
          },
        },
      ]
    );
  }

  async function handleDelete(t: Transaction) {
    setDeletingId(t.id);
    setError(null);
    try {
      await transactionsApi.remove(t.id);
      // Editing/deleting shifts category totals, the account balance, budget progress, goals, and
      // any insight built from spend patterns -- see invalidateFinancialData's own comment.
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not delete this transaction.'));
      hapticError();
    } finally {
      setDeletingId(null);
    }
  }

  async function handleUnmarkTransfer(t: Transaction) {
    setUnmarkingId(t.id);
    setError(null);
    try {
      await transactionsApi.unmarkTransfer(t.id);
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not unmark this transfer.'));
      hapticError();
    } finally {
      setUnmarkingId(null);
    }
  }

  return (
    <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top }]}>
      <View style={styles.header}>
        <View style={styles.headerText}>
          <Text style={[styles.title, { color: c.ink }]}>Transactions</Text>
          <Text style={[styles.subtitle, { color: c.mutedInk }]}>All your financial activity in one place.</Text>
        </View>
        <View style={styles.headerRight}>
          <Text style={[styles.count, { color: c.muted }]}>
            {/* Suppressed on a failed FIRST load as well as while loading. totalElements falls
                back to 0 when there are no pages, so a cold failure printed a confident "0 total"
                directly above this screen's own "Couldn't load your transactions." --
                contradicting, in the header, the rule the error branch below states explicitly
                ("a request that failed is not an answer of zero"). Scoped to txns.length === 0
                so a failed REFETCH, which keeps the previous pages, still shows their real count
                rather than blanking it. */}
            {isLoading || (isError && txns.length === 0)
              ? ''
              : `${totalElements.toLocaleString('en-IN')} total`}
          </Text>
          <Pressable
            onPress={() => setAddingTransaction(true)}
            hitSlop={10}
            accessibilityRole="button"
            accessibilityLabel="Add transaction"
          >
            <Ionicons name="add-circle" size={28} color={c.primary} />
          </Pressable>
        </View>
      </View>

      {/*
        Phase 7 (scroll-ratio redesign). Everything below used to sit as fixed siblings ABOVE the
        FlatList -- the summary card, search box, both filter rows, the date-range fields, the
        drill-through banner and the error text -- on every screen state, including loading and
        hard-error. That's what made this screen ~80% fixed chrome and ~20% actual scrollable list:
        none of it was inside the one scrollable region. It now all lives in ListHeaderComponent,
        so it scrolls away with the rest of the content exactly like the list rows do, leaving the
        transactions themselves the dominant use of the screen once scrolled -- same chrome, same
        show/hide conditions, just moved inside the scroll area instead of pinned above it.
      */}
      <FlatList
        testID="ledger-list"
        data={isLoading || (isError && txns.length === 0) ? [] : groupedRows}
        keyExtractor={(item) => (item.kind === 'header' ? `header-${item.date}` : item.transaction.id)}
        // Mirrors ImportScreen's own tuning (same three props, same reasoning there). No
        // getItemLayout: row height isn't fixed here -- it varies with description/merchant
        // text length and with the user's font-scale setting (useLargeFontScale above), and a
        // wrong precomputed offset would make FlatList jump to the wrong place on a long list,
        // not just skip the optimization.
        initialNumToRender={12}
        windowSize={9}
        removeClippedSubviews
        onEndReached={() => {
          if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
        }}
        onEndReachedThreshold={0.4}
        refreshing={isFetching && !isFetchingNextPage}
        onRefresh={() => void refetch()}
        contentContainerStyle={styles.listContent}
        ListHeaderComponent={
          <>
            {summary ? (
              <View style={styles.summaryWrap}>
                <LedgerMonthSummary kpis={snapshotKpis} deltaLabel={deltaLabel} />
              </View>
            ) : null}

            <View style={[styles.searchWrap, { backgroundColor: c.card, borderColor: c.border }]}>
              <Ionicons name="search" size={16} color={c.muted} style={styles.searchIcon} />
              <TextInput
                value={keywordInput}
                onChangeText={(text) => {
                  setKeywordInput(text);
                  // Real typing supersedes a drill-through-seeded keyword the moment it happens --
                  // same "wins until superseded" shape as Clear does for manualDateFrom/manualDateTo.
                  setDrillThroughKeyword(null);
                }}
                placeholder="Search description, merchant, bank…"
                placeholderTextColor={c.muted}
                autoCapitalize="none"
                autoCorrect={false}
                accessibilityLabel="Search transactions"
                style={[styles.search, { color: c.ink }]}
              />
            </View>

            {/* Type filter (All/Income/Expense) and status filter (Phase 4 -- reconciliationBadge's
                own status set as a filter, not just a per-row label) share one horizontally
                scrollable row -- two independent filters, same as before, just one visual line
                instead of two stacked ones. */}
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.statusFilterRow}
            >
              {(['ALL', 'INCOME', 'EXPENSE'] as TypeFilter[]).map((t) => (
                <Pressable
                  // Bug found in review: prefixed, not the bare filter value -- this and the
                  // status chips below are two separate arrays whose elements now render as
                  // FLATTENED SIBLINGS in the same ScrollView (merged into one visual row), and
                  // both arrays include the literal value 'ALL'. A bare `key={t}` collided with
                  // the status chips' own `key={s}` for 'ALL', which is a real key collision (not
                  // just a lint nit) -- two sibling elements sharing a key confuses React's
                  // reconciler across re-renders, e.g. the wrong chip's DOM/native instance being
                  // reused when either filter's selection changes.
                  key={`type-${t}`}
                  onPress={() => setTypeFilter(t)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: typeFilter === t }}
                  accessibilityLabel={`Filter: ${t === 'ALL' ? 'all' : t.toLowerCase()}`}
                  style={[
                    styles.chip,
                    { borderColor: c.border },
                    typeFilter === t && { backgroundColor: c.primaryLight, borderColor: c.primary },
                  ]}
                >
                  <Text style={[styles.chipText, { color: typeFilter === t ? c.primary : c.muted }]}>
                    {t === 'ALL' ? 'All' : t === 'INCOME' ? 'Income' : 'Expense'}
                  </Text>
                </Pressable>
              ))}
              {(['ALL', 'OK', ...STATUS_FILTERS] as StatusFilter[]).map((s) => {
                const label = s === 'ALL' ? 'All' : (reconciliationBadge(s)?.label ?? 'OK');
                const active = statusFilter === s;
                return (
                  <Pressable
                    key={`status-${s}`}
                    onPress={() => setStatusFilter(s)}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={`Filter by status: ${label}`}
                    style={[
                      styles.chip,
                      { borderColor: c.border },
                      active && { backgroundColor: c.primaryLight, borderColor: c.primary },
                    ]}
                  >
                    <Text style={[styles.chipText, { color: active ? c.primary : c.muted }]}>{label}</Text>
                  </Pressable>
                );
              })}
            </ScrollView>

            {/* Phase 5 (Low-Priority Polish). A manual date-range pick -- the drill-through banner
                below already shows a range when one arrives FROM elsewhere (a chart, a budget
                card), but there was no way to pick one by hand on this screen itself. Wins over the
                drill-through's own dates when set (see manualDateFrom's own doc comment above). */}
            <View style={styles.dateRangeRow}>
              <View style={styles.dateRangeField}>
                <DateField label="From" value={manualDateFrom} onChange={setManualDateFrom} />
              </View>
              <View style={styles.dateRangeField}>
                <DateField label="To" value={manualDateTo} onChange={setManualDateTo} />
              </View>
            </View>

            {/* Track C/C4. The drill-through this screen arrived with, if any -- shown rather than
                silently applied, since a filtered list with nothing on screen explaining WHY reads
                as "the ledger is broken", not "you drilled into Dining for August". Clearing it
                does not touch route.params (nothing here owns those -- they belong to whichever
                screen navigated in); it only resets this screen's own copy, the same way typing
                over the search field would. */}
            {activeDrillThrough ? (
              <View style={styles.filterRow}>
                <View style={[styles.drillChip, { borderColor: c.primary, backgroundColor: c.primaryLight }]}>
                  <Text style={[styles.drillChipText, { color: c.primary }]} numberOfLines={1}>
                    {activeDrillThrough.label}
                  </Text>
                  <Pressable
                    onPress={() => {
                      setActiveDrillThrough(null);
                      // A keyword-only drill-through (Insights' Top Merchant) has no OTHER field
                      // this banner's clear already resets -- without this, the banner disappears
                      // (looking cleared) while the search box and the results stay silently
                      // narrowed to the merchant that was cleared. Only touches the box if it
                      // still holds the seeded, unedited value (drillThroughKeyword is nulled the
                      // moment the user types their own search over it) -- their own typing is
                      // never clobbered by this button.
                      if (drillThroughKeyword !== null) {
                        setKeywordInput('');
                        setDrillThroughKeyword(null);
                      }
                    }}
                    hitSlop={8}
                    accessibilityRole="button"
                    accessibilityLabel={`Clear filter: ${activeDrillThrough.label}`}
                    style={styles.drillChipClearButton}
                  >
                    <Text style={[styles.drillChipClear, { color: c.primary }]}>✕</Text>
                  </Pressable>
                </View>
              </View>
            ) : null}

            {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
          </>
        }
        ListEmptyComponent={
          isLoading ? (
            <View>
              {Array.from({ length: 8 }).map((_, i) => (
                <SkeletonTransactionRow key={i} />
              ))}
            </View>
          ) : isError && txns.length === 0 ? (
            /**
             * A failed search must not fall through to the ordinary empty-state text below.
             * Without this branch `data` is [], and the list would otherwise render "No
             * transactions yet. Import a statement to get started." -- which tells someone who may
             * have years of imported history that they have none, and sends them to re-import data
             * they already own. Same class of bug as the dashboard's `!summary` guard: a request
             * that failed is not an answer of zero.
             *
             * Only when there is nothing on screen. A failure while paging is handled in the footer
             * instead, so one bad page cannot blank a list the user is already reading.
             */
            <View style={styles.centered}>
              <Text style={[styles.errorText, { color: c.muted }]}>Couldn't load your transactions.</Text>
              <Pressable onPress={() => void refetch()} hitSlop={12} accessibilityRole="button">
                <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
              </Pressable>
            </View>
          ) : (
            <Text style={[styles.empty, { color: c.muted }]}>
              {debouncedKeyword || typeFilter !== 'ALL' || statusFilter !== 'ALL' || activeDrillThrough
                || manualDateFrom || manualDateTo
                ? 'No transactions match these filters.'
                : 'No transactions yet. Import a statement to get started.'}
            </Text>
          )
        }
        ListFooterComponent={
          isFetchingNextPage ? (
            <ActivityIndicator style={styles.footer} color={c.primary} />
          ) : isError && txns.length > 0 ? (
            // Reached only with rows already on screen, since the empty case is handled above.
            // Silently stopping here would read as "you have reached the end", so say otherwise
            // and keep the rest of the list usable.
            <View style={styles.footer}>
              <Text style={[styles.errorText, { color: c.muted }]}>
                Couldn't load more transactions.
              </Text>
              <Pressable onPress={() => void fetchNextPage()} hitSlop={12} accessibilityRole="button">
                <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
              </Pressable>
            </View>
          ) : undefined
        }
        renderItem={({ item }) => {
            if (item.kind === 'header') {
              return (
                <View style={styles.dayHeader}>
                  <Text style={[styles.dayHeaderLabel, { color: c.mutedInk }]}>{item.label}</Text>
                  <Text style={[styles.dayHeaderSubtotal, { color: item.subtotal >= 0 ? c.success : c.danger }]}>
                    {item.subtotal >= 0 ? '+' : '-'}{fmtCurrency(Math.abs(item.subtotal))}
                  </Text>
                </View>
              );
            }
            const t = item.transaction;
            // Computed once per row rather than at each of its two call sites below -- it's a pure
            // function of two already-available fields, so there's nothing to gain from asking it
            // the same question twice.
            const cp = counterpartyLabel(t.counterpartyType, t.type);
            const badge = reconciliationBadge(t.reconciliationStatus);
            const badgeColors = badge ? {
              danger: { bg: c.dangerBg, fg: c.danger },
              primary: { bg: c.primaryLight, fg: c.primary },
              success: { bg: c.successBg, fg: c.success },
              warning: { bg: c.warningBg, fg: c.warning },
              muted: { bg: c.border, fg: c.mutedInk },
            }[badge.tone] : null;
            const badges = statusBadges(t);
            const badgeToneColors = {
              primary: { bg: c.primaryLight, fg: c.primary },
              success: { bg: c.successBg, fg: c.success },
              warning: { bg: c.warningBg, fg: c.warning },
              danger: { bg: c.dangerBg, fg: c.danger },
            } as const;
            return (
            <Pressable
              onPress={() => setViewingDetail(t)}
              onLongPress={() => confirmDelete(t)}
              style={[styles.row, { backgroundColor: c.card, borderColor: c.border }]}
              android_ripple={{ color: c.border }}
              // Long-press was the only route to delete, which made it unreachable for anyone
              // using a screen reader -- there's no gesture equivalent in the rotor. The hint below
              // tells sighted users the gesture exists at all, since nothing on the row advertises
              // it; a screen-reader user reaches the same delete action (and every other action
              // this row used to expose as its own nested icon button) as an independently
              // focusable row inside the detail sheet this opens instead -- see
              // TransactionDetailSheet.tsx's own doc comment for why that's a strictly better
              // reachability story than the accessibilityActions this replaces.
              accessibilityRole="button"
              accessibilityLabel={`${t.description || t.merchant || 'Transaction'}, ${
                t.type === 'INCOME' ? 'income' : 'expense'
              } ${fmtCurrency(Math.abs(t.amount))}, ${t.categoryName}, ${t.date}${
                // The FULL direction-composed reading, not the badge's short form: "Sent to a
                // person" is what someone listening to the row actually needs, and there is no
                // tooltip on a phone for them to reach for instead. Appended rather than inserted
                // so the existing announcement order is unchanged, and omitted entirely when the
                // counterparty is unknown -- padding every row in five with "unknown" would make
                // the whole list slower to listen to for no information gained.
                cp ? `, ${cp.full}` : ''
              }${
                // See reconciliationBadge's own comment above for why the hint travels here
                // rather than as a tooltip: there is nowhere else a screen-reader user could
                // otherwise learn it, since the pill below is grouped into this same atomic node.
                badge ? `, ${badge.hint}` : ''
              }, ${badges.map((b) => b.label).join(', ')}`}
              // Describes the OUTCOME, not the gesture: VoiceOver and TalkBack both append their
              // own "double tap to activate" to a button, so spelling the gesture out here had the
              // row announce the same instruction twice in conflicting words. Default activation
              // maps to onPress, so only the non-default (long-press) action needs a hint at all.
              accessibilityHint="Opens transaction details"
            >
              <View style={styles.logoWrap}>
                <MerchantLogo merchant={t.merchant || t.description || '?'} size={40} />
              </View>
              <View style={styles.rowMain}>
                <Text style={[styles.desc, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                  {t.description || t.merchant || 'Transaction'}
                </Text>
                <Text style={[styles.meta, { color: c.mutedInk }]} numberOfLines={1}>
                  {t.categoryName}
                  {/* WHO, next to WHAT it was for. The SHORT form here because this line is capped
                      at one line; the full direction-composed reading goes to the accessibility
                      label above, where there is no width to run out of and where a screen-reader
                      user has no tooltip to fall back on. Nothing at all when unknown. */}
                  {cp ? ` · ${cp.short}` : ''}
                  {' · '}
                  {t.date}
                </Text>
                {badge && badgeColors ? (
                  <Text
                    testID={`reconciliation-badge-${t.id}`}
                    style={[styles.reconciliationBadge, { backgroundColor: badgeColors.bg, color: badgeColors.fg }]}
                  >
                    {badge.label}
                  </Text>
                ) : null}
                {/* Phase 5. Independent of the reconciliation badge above -- that one is about a
                    MATCH (duplicate/transfer/refund/...), this one is about REVIEW STATE
                    (needs-review/recurring/reviewed/categorized). A row can carry both at once. */}
                <View style={styles.statusBadgeRow}>
                  {badges.map((b) => (
                    <Text
                      key={b.label}
                      style={[
                        styles.reconciliationBadge,
                        { backgroundColor: badgeToneColors[b.tone].bg, color: badgeToneColors[b.tone].fg },
                      ]}
                    >
                      {b.label}
                    </Text>
                  ))}
                </View>
              </View>
              {deletingId === t.id ? (
                <ActivityIndicator size="small" color={c.muted} style={styles.rowTrailingSpacing} />
              ) : (
                <Text style={[styles.amount, { color: t.type === 'INCOME' ? c.success : c.danger }, styles.rowTrailingSpacing]}>
                  {t.type === 'INCOME' ? '+' : '-'}
                  {fmtCurrency(Math.abs(t.amount))}
                </Text>
              )}
              {/* Purely visual now that every action a tap used to reach directly (via the row's
                  own inline icon buttons) lives in the detail sheet this row's onPress opens --
                  the chevron signals "tap for more", it isn't an independent touch target. */}
              <Ionicons name="chevron-forward" size={16} color={c.muted} />
            </Pressable>
            );
          }}
        />

      <TransactionSourceModal transactionId={viewingSourceId} onClose={() => setViewingSourceId(null)} />

      {/* Bug found in review: without a `key` this is a single persistent instance reused across
          every transaction, so its local marking/error state would survive from one viewed
          transaction into the next. Keying by transaction forces a clean remount on every open. */}
      <MarkTransferModal
        key={markingTransfer?.id ?? 'none'}
        transaction={markingTransfer}
        onClose={() => setMarkingTransfer(null)}
        onMarked={() => { setMarkingTransfer(null); invalidateFinancialData(queryClient); }}
      />

      <TransactionExplanationModal
        transactionId={explaining?.id ?? null}
        category={explaining?.category ?? null}
        onClose={() => setExplaining(null)}
      />

      {/* Bug found in review: without a `key` here, this component is a single persistent
          instance reused across every transaction (it's given no key elsewhere in this file
          either, matching MarkTransferModal's own shape) -- its local acknowledging/error state
          would otherwise survive from one viewed transaction into the next. Keying by which
          transaction is being viewed forces a clean remount (fresh state) on every open, the
          same fix React's own docs recommend over resetting state manually inside an effect. */}
      <BankCorrectionModal
        key={viewingCorrection?.id ?? 'none'}
        transaction={viewingCorrection}
        onClose={() => setViewingCorrection(null)}
        onAcknowledged={() => { setViewingCorrection(null); invalidateFinancialData(queryClient); }}
      />

      {editingTransaction ? (
        <EditTransactionSheet
          transaction={editingTransaction}
          onClose={() => setEditingTransaction(null)}
          onSaved={() => setEditingTransaction(null)}
        />
      ) : null}

      {addingTransaction ? (
        <AddTransactionSheet
          onClose={() => setAddingTransaction(false)}
          onSaved={() => setAddingTransaction(false)}
        />
      ) : null}

      {/* Seeded with the row's current category so the sheet opens showing what it is now, not a
          blank slate -- the user is correcting an answer, not supplying a missing one. */}
      <OptionPickerModal
        visible={recategorizing !== null}
        title="Change category"
        options={categories.map((x) => x.name)}
        selected={recategorizing?.categoryName ?? null}
        onSelect={(name) => {
          const target = recategorizing;
          if (target) void applyCategory(target, name);
        }}
        onClose={() => setRecategorizing(null)}
      />

      {viewingDetail ? (
        <TransactionDetailSheet
          transaction={viewingDetail}
          onClose={() => setViewingDetail(null)}
          onChangeCategory={() => { setViewingDetail(null); setRecategorizing(viewingDetail); }}
          onEdit={() => { setViewingDetail(null); setEditingTransaction(viewingDetail); }}
          onViewSource={() => { setViewingDetail(null); setViewingSourceId(viewingDetail.id); }}
          onExplainCategory={() => {
            setViewingDetail(null);
            setExplaining({ id: viewingDetail.id, category: viewingDetail.categoryName });
          }}
          onMarkTransfer={() => { setViewingDetail(null); setMarkingTransfer(viewingDetail); }}
          // Same reasoning as onDelete below: deferred until the request settles, not fired the
          // instant the row is pressed, so the sheet's own "unmarking" spinner state is actually
          // observable instead of unmounting in the same render it would first turn true.
          onUnmarkTransfer={() => void handleUnmarkTransfer(viewingDetail).finally(() => setViewingDetail(null))}
          unmarking={unmarkingId === viewingDetail.id}
          onViewCorrection={() => { setViewingDetail(null); setViewingCorrection(viewingDetail); }}
          onDelete={() => confirmDelete(viewingDetail)}
          deleting={deletingId === viewingDetail.id}
        />
      ) : null}
    </View>
  );
}

/**
 * Redesign's own compact "This Month" card, screen-specific rather than a change to the shared
 * LedgerSnapshotCard (Dashboard/AccountsCard both still use that one, 4-row passbook layout
 * unchanged). Reuses the exact same computed KpiItem values useDashboardKpis already produces --
 * just Income and Expenses, side by side with a divider, instead of all four in a vertical list.
 * Keeps LedgerSnapshotCard's own `kpi-${label}` testID convention so the existing "This Month"
 * summary tests need no changes.
 */
function LedgerMonthSummary({ kpis, deltaLabel }: { kpis: KpiItem[]; deltaLabel: string }) {
  const c = useTheme();
  const income = kpis.find((k) => k.label === 'Income');
  const expenses = kpis.find((k) => k.label === 'Expenses');
  if (!income || !expenses) return null;

  function column(kpi: KpiItem) {
    return (
      <View style={styles.monthCol}>
        <Text style={[styles.monthLabel, { color: c.mutedInk }]}>{kpi.label}</Text>
        <AnimatedNumber testID={`kpi-${kpi.label}`} value={kpi.value} style={[styles.monthValue, { color: c.ink }]} />
        {kpi.delta !== null && kpi.delta !== undefined ? (
          <Text style={[styles.monthDelta, { color: (kpi.invert ? kpi.delta < 0 : kpi.delta >= 0) ? c.success : c.danger }]}>
            {kpi.delta >= 0 ? '▲' : '▼'} {Math.abs(kpi.delta).toFixed(1)}% {deltaLabel}
          </Text>
        ) : null}
      </View>
    );
  }

  return (
    <View>
      <Text style={[styles.monthLabel, { color: c.ink, fontWeight: '700', marginBottom: spacing.xs }]}>This Month</Text>
      <View style={[styles.monthCard, { backgroundColor: c.primaryLight, borderColor: c.border }]}>
        {column(income)}
        <View style={[styles.monthDivider, { backgroundColor: c.border }]} />
        {column(expenses)}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.sm,
  },
  headerText: { flex: 1, marginRight: spacing.sm },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 2 },
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  count: { fontSize: 12 },
  summaryWrap: { paddingHorizontal: spacing.md, paddingTop: spacing.sm },
  monthCard: {
    flexDirection: 'row',
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.md,
  },
  monthCol: { flex: 1 },
  monthDivider: { width: StyleSheet.hairlineWidth, marginHorizontal: spacing.md },
  monthLabel: { fontSize: 13, marginBottom: 2 },
  monthValue: { fontSize: 18 },
  monthDelta: { fontSize: 11, marginTop: 2 },
  // Bug found in review: the icon used to be a position:'absolute' sibling layered on top of the
  // TextInput's own left padding -- React Native resolves an absolute element's position by first
  // computing where it would land AS IF it were position:'relative' (honoring the parent's own
  // flex alignment), THEN applying top/left/right/bottom as an OFFSET from that computed point --
  // a model that differs from ordinary web CSS absolute positioning enough that getting vertical
  // centering right here wasn't something to trust without visually verifying it, which this
  // worktree cannot do (no simulator available). A plain flex row with the icon and input as
  // ordinary siblings has no such ambiguity: the icon centers via alignItems the same way any
  // other icon-plus-text row in this app already does.
  searchWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: spacing.md,
    marginTop: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
  },
  searchIcon: { marginRight: spacing.xs },
  search: {
    flex: 1,
    paddingVertical: 10,
    fontSize: 14,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  statusFilterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.sm,
  },
  dateRangeRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
  },
  dateRangeField: { flex: 1 },
  chip: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 16,
    // 44pt is the minimum comfortable touch target on both platforms (Apple HIG and Material
    // both land there). These were ~30pt tall, which is a miss-prone target for a filter people
    // toggle repeatedly while scanning a list.
    minHeight: 44,
    justifyContent: 'center',
  },
  chipText: { fontSize: 12, fontWeight: '600' },
  // Track C/C4.
  drillChip: {
    flexDirection: 'row', alignItems: 'center', gap: 8,
    borderWidth: 1, borderRadius: 999, paddingHorizontal: 14, minHeight: 36,
  },
  drillChipText: { fontSize: 12, fontWeight: '600' },
  // 28x28 box + hitSlop 8 on every side = 44x44 effective touch target (WCAG 2.5.5 / HIG minimum).
  // A bare hitSlop around the unsized ✕ glyph left ~29x29 -- font rendering isn't a reliable box.
  drillChipClearButton: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
  drillChipClear: { fontSize: 13, fontWeight: '700' },
  error: { fontSize: 13, paddingHorizontal: spacing.md, paddingBottom: spacing.sm },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  listContent: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
  dayHeader: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline',
    paddingTop: spacing.md, paddingBottom: spacing.xs,
  },
  dayHeaderLabel: { fontSize: 13, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.3 },
  dayHeaderSubtotal: { fontSize: 12, fontWeight: '600' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.sm,
  },
  logoWrap: { marginRight: spacing.sm },
  rowMain: { flex: 1, marginRight: spacing.sm },
  desc: { fontSize: 14, fontWeight: '500' },
  meta: { fontSize: 11, marginTop: 2 },
  reconciliationBadge: {
    alignSelf: 'flex-start',
    fontSize: 10,
    fontWeight: '700',
    textTransform: 'uppercase',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: radius.md,
    marginTop: 4,
    overflow: 'hidden',
  },
  statusBadgeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 4 },
  amount: { fontSize: 14, fontWeight: '700' },
  rowTrailingSpacing: { marginRight: spacing.xs },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.xl },
  footer: { paddingVertical: spacing.md, alignItems: 'center', gap: spacing.xs },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
});
