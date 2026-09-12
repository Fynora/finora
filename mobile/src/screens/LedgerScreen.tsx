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
import { DateField } from '../components/DateField';
import { LedgerSnapshotCard } from '../components/dashboard/LedgerSnapshotCard';
import { MarkTransferModal } from '../components/MarkTransferModal';
import { MerchantLogo } from '../components/MerchantLogo';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TransactionExplanationModal } from '../components/TransactionExplanationModal';
import { TransactionSourceModal } from '../components/TransactionSourceModal';
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { AddTransactionSheet } from './AddTransactionSheet';
import { EditTransactionSheet } from './EditTransactionSheet';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { toUserMessage } from '../lib/apiError';
import { hapticError, hapticImpact, hapticSuccess } from '../lib/haptics';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { fmtCurrency, fromLocalDateString, toLocalDateString } from '../lib/format';
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
 */
function statusBadges(t: Transaction): { label: string; tone: 'warning' | 'primary' | 'success' }[] {
  const badges: { label: string; tone: 'warning' | 'primary' | 'success' }[] = [];
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
      (sum, t) => sum + (t.type === 'INCOME' ? t.amount : -Math.abs(t.amount)),
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
  const { snapshotKpis, deltaLabel, deltaSpokenLabel } = useDashboardKpis(summary);

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
        { text: 'Delete', style: 'destructive', onPress: () => void handleDelete(t) },
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
        <Text style={[styles.title, { color: c.ink }]}>Transactions</Text>
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

      {summary ? (
        <View style={styles.summaryWrap}>
          <LedgerSnapshotCard kpis={snapshotKpis} deltaLabel={deltaLabel} deltaSpokenLabel={deltaSpokenLabel} />
        </View>
      ) : null}

      <TextInput
        value={keywordInput}
        onChangeText={(text) => {
          setKeywordInput(text);
          // Real typing supersedes a drill-through-seeded keyword the moment it happens -- same
          // "wins until superseded" shape as Clear does for manualDateFrom/manualDateTo.
          setDrillThroughKeyword(null);
        }}
        placeholder="Search description, merchant, bank…"
        placeholderTextColor={c.muted}
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel="Search transactions"
        style={[styles.search, { backgroundColor: c.card, borderColor: c.border, color: c.ink }]}
      />

      <View style={styles.filterRow}>
        {(['ALL', 'INCOME', 'EXPENSE'] as TypeFilter[]).map((t) => (
          <Pressable
            key={t}
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
      </View>

      {/* Phase 4 -- reconciliationBadge's own status set as a filter, not just a per-row label.
          Horizontally scrollable: 6 real statuses plus 'ALL' don't fit typeFilter's fixed 3-chip
          row, and this screen has no other use for horizontal scroll to collide with. */}
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.statusFilterRow}
      >
        {(['ALL', 'OK', ...STATUS_FILTERS] as StatusFilter[]).map((s) => {
          const label = s === 'ALL' ? 'All' : (reconciliationBadge(s)?.label ?? 'OK');
          const active = statusFilter === s;
          return (
            <Pressable
              key={s}
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

      {/* Phase 5 (Low-Priority Polish). A manual date-range pick -- the drill-through banner below
          already shows a range when one arrives FROM elsewhere (a chart, a budget card), but there
          was no way to pick one by hand on this screen itself. Wins over the drill-through's own
          dates when set (see manualDateFrom's own doc comment above). */}
      <View style={styles.dateRangeRow}>
        <View style={styles.dateRangeField}>
          <DateField label="From" value={manualDateFrom} onChange={setManualDateFrom} />
        </View>
        <View style={styles.dateRangeField}>
          <DateField label="To" value={manualDateTo} onChange={setManualDateTo} />
        </View>
      </View>

      {/* Track C/C4. The drill-through this screen arrived with, if any -- shown rather than
          silently applied, since a filtered list with nothing on screen explaining WHY reads as
          "the ledger is broken", not "you drilled into Dining for August". Clearing it does not
          touch route.params (nothing here owns those -- they belong to whichever screen navigated
          in); it only resets this screen's own copy, the same way typing over the search field
          would. */}
      {activeDrillThrough ? (
        <View style={styles.filterRow}>
          <View style={[styles.drillChip, { borderColor: c.primary, backgroundColor: c.primaryLight }]}>
            <Text style={[styles.drillChipText, { color: c.primary }]} numberOfLines={1}>
              {activeDrillThrough.label}
            </Text>
            <Pressable
              onPress={() => {
                setActiveDrillThrough(null);
                // A keyword-only drill-through (Insights' Top Merchant) has no OTHER field this
                // banner's clear already resets -- without this, the banner disappears (looking
                // cleared) while the search box and the results stay silently narrowed to the
                // merchant that was cleared. Only touches the box if it still holds the seeded,
                // unedited value (drillThroughKeyword is nulled the moment the user types their
                // own search over it) -- their own typing is never clobbered by this button.
                if (drillThroughKeyword !== null) {
                  setKeywordInput('');
                  setDrillThroughKeyword(null);
                }
              }}
              hitSlop={8}
              accessibilityRole="button"
              accessibilityLabel={`Clear filter: ${activeDrillThrough.label}`}
            >
              <Text style={[styles.drillChipClear, { color: c.primary }]}>✕</Text>
            </Pressable>
          </View>
        </View>
      ) : null}

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

      {isLoading ? (
        // ScrollView, not a plain View -- the FlatList is this screen's only other scrollable
        // region and doesn't exist yet during this branch, so a plain View here would silently
        // clip the bottom skeleton rows on shorter-viewport devices once search/filter chrome
        // above eats into the available height.
        <ScrollView contentContainerStyle={styles.listContent}>
          {Array.from({ length: 8 }).map((_, i) => (
            <SkeletonTransactionRow key={i} />
          ))}
        </ScrollView>
      ) : isError && txns.length === 0 ? (
        /**
         * A failed search must not fall through to ListEmptyComponent below. Without this branch
         * `data` is undefined, `txns` is [], and the list renders "No transactions yet. Import a
         * statement to get started." -- which tells someone who may have years of imported history
         * that they have none, and sends them to re-import data they already own. Same class of bug
         * as the dashboard's `!summary` guard: a request that failed is not an answer of zero.
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
        <FlatList
          testID="ledger-list"
          data={groupedRows}
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
          ListEmptyComponent={
            <Text style={[styles.empty, { color: c.muted }]}>
              {debouncedKeyword || typeFilter !== 'ALL' || statusFilter !== 'ALL' || activeDrillThrough
                || manualDateFrom || manualDateTo
                ? 'No transactions match these filters.'
                : 'No transactions yet. Import a statement to get started.'}
            </Text>
          }
          ListFooterComponent={
            isFetchingNextPage ? (
              <ActivityIndicator style={styles.footer} color={c.primary} />
            ) : isError ? (
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
            } as const;
            // Built as a plain local array, not inlined with a spread inside the JSX prop below --
            // eslint-plugin-react-native-a11y's has-valid-accessibility-actions rule can only
            // statically walk a literal ArrayExpression of object literals and crashes (not just
            // mis-lints) on a spread/ternary inside one. Same four base actions every row has,
            // plus mark/unmark transfer, mirroring reconciliationBadge below's own condition.
            const accessibilityActions: { name: string; label: string }[] = [
              { name: 'delete', label: 'Delete transaction' },
              { name: 'viewSource', label: 'Show where this came from' },
              { name: 'edit', label: 'Edit transaction' },
              { name: 'explain', label: 'Why this category' },
            ];
            if (t.reconciliationStatus === 'TRANSFER') {
              accessibilityActions.push({ name: 'unmarkTransfer', label: 'Unmark as transfer' });
            } else if (t.reconciliationStatus === 'OK') {
              accessibilityActions.push({ name: 'markTransfer', label: 'Mark as transfer' });
            }
            return (
            <Pressable
              onPress={() => setRecategorizing(t)}
              onLongPress={() => confirmDelete(t)}
              style={[styles.row, { backgroundColor: c.card, borderColor: c.border }]}
              android_ripple={{ color: c.border }}
              // Long-press was the only route to delete, which made it unreachable for anyone
              // using a screen reader -- there's no gesture equivalent in the rotor. Declaring it
              // as an accessibility action exposes it properly, and the hint tells sighted users
              // the gesture exists at all, since nothing on the row advertises it.
              //
              // Tap now opens the category picker, so it gets the same treatment: an explicit
              // action as well as the hint, because "changing a category" is the row's primary
              // action and a screen reader user shouldn't have to discover it by guessing.
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
              // row announce the same instruction twice in conflicting words -- and the standard
              // 'activate' action that used to sit in the list below advertised it a third time,
              // as a rotor entry duplicating what a plain double-tap already does. Default
              // activation maps to onPress, so only the non-default action needs declaring.
              accessibilityHint="Changes this transaction's category"
              // Track C/C7's info button below is a SIGHTED-only affordance, not a second
              // accessibility stop: nesting an accessible Pressable inside one that's already
              // accessible={true} (the default neither opts out of) doesn't create a separate
              // screen-reader-reachable node on either platform -- VoiceOver/TalkBack treat the
              // whole subtree as one atomic element, and activating it fires THIS Pressable's own
              // onPress, not the nested one's. 'viewSource' is the same fix already applied to
              // 'delete' above for the identical reason: a rotor action reaches it either way.
              accessibilityActions={accessibilityActions}
              onAccessibilityAction={(e) => {
                if (e.nativeEvent.actionName === 'delete') confirmDelete(t);
                if (e.nativeEvent.actionName === 'viewSource') setViewingSourceId(t.id);
                if (e.nativeEvent.actionName === 'edit') setEditingTransaction(t);
                if (e.nativeEvent.actionName === 'explain') setExplaining({ id: t.id, category: t.categoryName });
                if (e.nativeEvent.actionName === 'unmarkTransfer') void handleUnmarkTransfer(t);
                if (e.nativeEvent.actionName === 'markTransfer') setMarkingTransfer(t);
              }}
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
                <ActivityIndicator size="small" color={c.muted} />
              ) : (
                <Text style={[styles.amount, { color: t.type === 'INCOME' ? c.success : c.danger }]}>
                  {t.type === 'INCOME' ? '+' : '-'}
                  {fmtCurrency(Math.abs(t.amount))}
                </Text>
              )}
              {/* Track C/C7. Nested inside the row's own Pressable -- RN gives the innermost
                  touch target the tap, so this doesn't collide with onPress/onLongPress above,
                  for a SIGHTED user. Deliberately `accessible={false}`: the outer row's own
                  accessible={true} (default) already makes its whole subtree one atomic
                  VoiceOver/TalkBack element, so this nested Pressable can never be an
                  independently reachable second stop regardless of its own accessibilityLabel --
                  the 'viewSource' accessibilityAction declared on the outer row above is the
                  real, reachable path for a screen-reader user. */}
              <Pressable
                onPress={() => setViewingSourceId(t.id)}
                hitSlop={4}
                style={styles.sourceButton}
                accessible={false}
                testID={`source-button-${t.id}`}
              >
                <Ionicons name="information-circle-outline" size={18} color={c.muted} />
              </Pressable>
              {/* Full edit (date/amount/merchant/type/category/notes/tags) -- the row's own
                  tap/long-press are already spoken for (recategorize/delete), so this gets its
                  own icon rather than a third overloaded gesture. Same accessible={false}
                  reasoning as the info button just above: the outer row's 'edit' accessibility
                  action (declared above) is the real reachable path for a screen-reader user. */}
              <Pressable
                onPress={() => setEditingTransaction(t)}
                hitSlop={4}
                style={styles.sourceButton}
                accessible={false}
                testID={`edit-button-${t.id}`}
              >
                <Ionicons name="pencil-outline" size={18} color={c.muted} />
              </Pressable>
              {/* Phase 4's "Why this category?" panel -- same nested, accessible={false} pattern
                  as the source/edit buttons above, for the identical reason: the row's tap/
                  long-press are already spoken for, and 'explain' (declared above) is the real
                  reachable path for a screen-reader user. */}
              <Pressable
                onPress={() => setExplaining({ id: t.id, category: t.categoryName })}
                hitSlop={4}
                style={styles.sourceButton}
                accessible={false}
                testID={`explain-button-${t.id}`}
              >
                <Ionicons name="help-circle-outline" size={18} color={c.muted} />
              </Pressable>
              {/* Phase 6. Same nested, accessible={false} pattern as the three buttons above --
                  the outer row's 'markTransfer'/'unmarkTransfer' accessibility action (declared
                  above) is the real reachable path for a screen-reader user. Only one of the two
                  ever renders, mirroring the accessibilityActions array's own condition. */}
              {t.reconciliationStatus === 'TRANSFER' ? (
                <Pressable
                  onPress={() => void handleUnmarkTransfer(t)}
                  disabled={unmarkingId === t.id}
                  hitSlop={4}
                  style={styles.sourceButton}
                  accessible={false}
                  testID={`unmark-transfer-button-${t.id}`}
                >
                  <Ionicons name="swap-horizontal" size={18} color={c.muted} />
                </Pressable>
              ) : t.reconciliationStatus === 'OK' ? (
                <Pressable
                  onPress={() => setMarkingTransfer(t)}
                  hitSlop={4}
                  style={styles.sourceButton}
                  accessible={false}
                  testID={`mark-transfer-button-${t.id}`}
                >
                  <Ionicons name="swap-horizontal-outline" size={18} color={c.muted} />
                </Pressable>
              ) : null}
            </Pressable>
            );
          }}
        />
      )}

      <TransactionSourceModal transactionId={viewingSourceId} onClose={() => setViewingSourceId(null)} />

      <MarkTransferModal
        transaction={markingTransfer}
        onClose={() => setMarkingTransfer(null)}
        onMarked={() => { setMarkingTransfer(null); invalidateFinancialData(queryClient); }}
      />

      <TransactionExplanationModal
        transactionId={explaining?.id ?? null}
        category={explaining?.category ?? null}
        onClose={() => setExplaining(null)}
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
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.md,
    paddingTop: spacing.sm,
  },
  title: { fontSize: 22, fontWeight: '700' },
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  count: { fontSize: 12 },
  summaryWrap: { paddingHorizontal: spacing.md, paddingTop: spacing.sm },
  search: {
    marginHorizontal: spacing.md,
    marginTop: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
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
  // marginLeft 8 (spacing.sm) pairs with each button's own hitSlop={4} below: 4+4=8 exactly
  // meets the gap, so neighboring row-action icons' hit regions touch but never overlap -- at
  // marginLeft: spacing.xs (4) with hitSlop={10}, adjacent buttons' hit regions overlapped by
  // ~16pt, so a tap aimed at one could land on its neighbor instead.
  sourceButton: { marginLeft: spacing.sm, padding: 2 },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.xl },
  footer: { paddingVertical: spacing.md, alignItems: 'center', gap: spacing.xs },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
});
