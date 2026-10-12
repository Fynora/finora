import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View,
  type NativeScrollEvent, type NativeSyntheticEvent, type TextInput,
} from 'react-native';
import { AppAlert } from '../lib/appAlert';
import { askCategoryScope } from '../lib/askCategoryScope';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRoute, type RouteProp } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { GlassScreen } from '../components/GlassScreen';
import { useGlassSurfaceStyle } from '../components/GlassSurface';
import {
  categoriesApi, dashboardApi, onboardingApi, transactionsApi, type PagedResponse, type TransactionFilters,
} from '../api/endpoints';
import { BankCorrectionModal } from '../components/BankCorrectionModal';
import { CategoryPickerModal } from '../components/CategoryPickerModal';
import { MarkTransferModal } from '../components/MarkTransferModal';
import { TransactionExplanationModal } from '../components/TransactionExplanationModal';
import { TransactionSourceModal } from '../components/TransactionSourceModal';
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { LedgerControls, type ActiveFilterChip, type TypeFilter } from '../components/ledger/LedgerControls';
import { LedgerDayCard } from '../components/ledger/LedgerDayCard';
import { LedgerFiltersSheet, statusFilterLabel, type StatusFilter } from '../components/ledger/LedgerFiltersSheet';
import { LedgerMonthCard } from '../components/ledger/LedgerMonthCard';
import { LedgerPinnedBar, LedgerTitleBlock, PINNED_BAR_SOLID_AFTER } from '../components/ledger/LedgerTitleBar';
import { AddTransactionSheet } from './AddTransactionSheet';
import { EditTransactionSheet } from './EditTransactionSheet';
import { TransactionDetailSheet } from './TransactionDetailSheet';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { hapticError, hapticImpact, hapticSuccess } from '../lib/haptics';
import { useDashboardKpis } from '../lib/useDashboardKpis';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { buildDayPieces, mergePagesById, type DayPiece } from '../lib/ledgerRows';
import { fmtCurrency, fmtDate } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList, LedgerDrillThroughFilters } from '../navigation/types';
import type { Transaction } from '../types';

export const LEDGER_PAGE_SIZE = 20;
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

/**
 * What one page request is identified by. The first page is the number 0. Every later page names
 * the last row already held (`after`) and also carries its page number.
 *
 * A page number alone counts rows from the top of the list, so a transaction added or deleted on
 * the server between two requests (an import finishing, an edit on another device) shifted every
 * later page by one row: the next page repeated a row, or stepped over one. `after` asks for "the
 * rows that follow this one", which does not count from the top. The number still goes along for a
 * server that does not know `after` and pages by number.
 *
 * A bare number can also come back from a cache saved by an older build (queryPersistence).
 */
export type LedgerPageParam = number | { page: number; after: string };

/** The request params for one page param -- shared with the Dashboard prefetch, like the two below. */
export function ledgerPageRequest(pageParam: LedgerPageParam): { page: number; after?: string } {
  return typeof pageParam === 'number' ? { page: pageParam } : { page: pageParam.page, after: pageParam.after };
}

/** Exported for the same reason as DEFAULT_LEDGER_FILTERS -- prefetchInfiniteQuery needs the
 *  identical pagination cursor logic this screen's own useInfiniteQuery uses below, so a
 *  prefetched page and a screen-fetched page agree on whether there's a next one.
 *
 *  "Is there more" stays `page + 1 < totalPages` whichever way the server answered: paged by
 *  number it reports this page's number against the whole list, and continued from a row it
 *  reports page 0 of what is left. The next page's own number is therefore counted here, from the
 *  pages already held, not read off the response. */
export function getLedgerNextPageParam(
  lastPage: PagedResponse<Transaction>,
  allPages: PagedResponse<Transaction>[],
): LedgerPageParam | undefined {
  if (lastPage.page + 1 >= lastPage.totalPages) return undefined;
  const lastRow = lastPage.content[lastPage.content.length - 1];
  // No row to continue from (a page that came back empty while more is promised): by number.
  return lastRow ? { page: allPages.length, after: lastRow.id } : allPages.length;
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

// Moved to lib/ledgerRows.ts with the rest of the row logic; re-exported so existing importers
// and this screen's own tests keep their import.
export { groupTransactionsByDay } from '../lib/ledgerRows';

export function LedgerScreen() {
  // D3 (Track D security cleanup). Every row here is a real transaction description and amount --
  // the same screenshot/screen-recording exposure Dashboard, Accounts, and Statement History
  // already guard against, just not yet extended to the Ledger itself.
  usePreventScreenCapture();
  const c = useTheme();
  // The card the loading, failed and empty states sit in: the same fill and edge as a day's card,
  // solid fallback included.
  const glassRow = useGlassSurfaceStyle();
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
  // The statement's own domestic/international split (Transaction.international). A single
  // on/off switch (in the Filters sheet) rather than web's three-way select: "only international" is the question a phone
  // user asks; the web Ledger's select also offers domestic-only.
  const [internationalOnly, setInternationalOnly] = useState(false);
  // Phase 5 (Low-Priority Polish). A manual date-range pick, independent of the drill-through's
  // OWN dateFrom/dateTo below -- a drill-through arrives already scoped to a period (e.g. "August
  // 2026" from a chart), while this is the user picking their own range by hand. Wins over the
  // drill-through's dates when set, since it's the more RECENT, more deliberate choice; clearing
  // it (DateField's own "Clear" link, the date chip, or Clear all) falls back to whatever the drill-through, if any, still says.
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
  const [explaining, setExplaining] = useState<{ id: string; category: string; credit: boolean } | null>(null);
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
  const [filtersOpen, setFiltersOpen] = useState(false);
  // The pinned bar: solid once the list has moved, its small title once the large one has gone.
  const [barSolid, setBarSolid] = useState(false);
  const [barCompact, setBarCompact] = useState(false);
  // The large title's measured height. Until it is measured, a height that is right at the
  // default text size.
  const titleHeight = useRef(60);
  const listRef = useRef<FlatList<DayPiece>>(null);
  const searchRef = useRef<TextInput>(null);

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
  // until the user edits the search box by hand (cleared in LedgerControls' onKeywordChange below),
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
    // the "Clear filter: <new drill-through's label>" chip would show the new period while the
    // list itself stayed silently scoped to whatever date range was picked by hand before it --
    // exactly the kind of "the filter says one thing, the results say another" bug this screen's
    // own drill-through chip exists to prevent. A fresh drill-through is a new, more recent,
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
  const { snapshotKpis, periodTitle, deltaLabel, deltaSpokenLabel } = useDashboardKpis(summary);

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
      international: internationalOnly ? true : undefined,
      // accountId: Track C/C6 (ImportScreen's "View in Ledger") is the only caller that ever sets
      // this -- needs no name resolution, since ImportScreen already has the confirmed account's
      // real id from the confirm response itself.
      accountId: activeDrillThrough?.accountId,
      categoryId: resolvedCategoryId,
      dateFrom: manualDateFrom ?? activeDrillThrough?.dateFrom ?? undefined,
      dateTo: manualDateTo ?? activeDrillThrough?.dateTo ?? undefined,
    }),
    [drillThroughKeyword, debouncedKeyword, typeFilter, statusFilter, internationalOnly, resolvedCategoryId, activeDrillThrough, manualDateFrom, manualDateTo]
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
    queryFn: ({ pageParam }) => transactionsApi.search({ ...filters, ...ledgerPageRequest(pageParam) }),
    initialPageParam: 0 as LedgerPageParam,
    // The backend's PagedResponse carries a real totalPages, so "is there more" is answered by
    // the server rather than inferred from whether a page came back full.
    getNextPageParam: getLedgerNextPageParam,
  });

  // Each transaction once: the server has returned the same row on two pages (see mergePagesById).
  // Memoized on `data` itself, which is stable across renders where the query result has not
  // changed, so `pieces` below is not rebuilt by an unrelated render either.
  const txns = useMemo(() => mergePagesById(data?.pages ?? []), [data]);
  // The first page's count is the whole list's: a page continued from a row counts only what is left.
  const totalElements = data?.pages[0]?.totalElements ?? 0;
  // Computed fresh every render (cheap: one Date construction) and listed below as a dependency in
  // its own right. Without it, a mount that goes idle overnight -- this tab stays mounted, and a
  // foreground refetch that returns unchanged data does not change txns either -- would keep
  // showing whichever "Today"/"Yesterday" it computed the last time txns itself changed. Naming it
  // here means any re-render after midnight corrects the headings.
  const todayDateKey = new Date().toDateString();
  // `todayDateKey` isn't read inside the callback -- buildDayPieces reads the clock itself through
  // its own `today` default -- it is listed purely to force a recompute once the day rolls over;
  // eslint's exhaustive-deps can't see that reasoning.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const pieces = useMemo(() => buildDayPieces(txns), [txns, todayDateKey]);

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
    const scope = await askCategoryScope(t.id);
    if (scope === null) return;
    const startedAt = requestStartedAt();
    try {
      await transactionsApi.updateCategory(t.id, categoryName, scope);
      hapticSuccess();
      // Not an optimistic edit: the row stays put and only its label changes, so there is no
      // felt latency to hide -- and a category move shifts spend-by-category, budget progress
      // and insights, none of which this screen can guess correctly on its own.
      invalidateFinancialData(queryClient);
    } catch (e) {
      reportTransportFailure(e, 'ledger:apply-category', startedAt);
      setError(toUserMessage(e, 'Could not change this category.'));
      hapticError();
    }
  }

  // Both are useCallbacks so the list's memoised rows are handed the same functions on every
  // render; handleDelete is declared first because confirmDelete depends on it.
  const handleDelete = useCallback(async (t: Transaction) => {
    setDeletingId(t.id);
    setError(null);
    const startedAt = requestStartedAt();
    try {
      await transactionsApi.remove(t.id);
      // Editing/deleting shifts category totals, the account balance, budget progress, goals, and
      // any insight built from spend patterns -- see invalidateFinancialData's own comment.
      invalidateFinancialData(queryClient);
    } catch (e) {
      reportTransportFailure(e, 'ledger:delete-transaction', startedAt);
      setError(toUserMessage(e, 'Could not delete this transaction.'));
      hapticError();
    } finally {
      setDeletingId(null);
    }
  }, [queryClient]);

  const confirmDelete = useCallback((t: Transaction) => {
    // Before the alert, not after a choice is made -- the same convention iOS's own system apps
    // use for a press that's about to open a destructive confirmation, so the gesture itself
    // feels acknowledged rather than only its eventual outcome.
    hapticImpact();
    // Alert.alert replaces the web's window.confirm(), which doesn't exist in React Native.
    AppAlert.alert(
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
  }, [handleDelete]);

  async function handleUnmarkTransfer(t: Transaction) {
    setUnmarkingId(t.id);
    setError(null);
    const startedAt = requestStartedAt();
    try {
      await transactionsApi.unmarkTransfer(t.id);
      invalidateFinancialData(queryClient);
    } catch (e) {
      reportTransportFailure(e, 'ledger:unmark-transfer', startedAt);
      setError(toUserMessage(e, 'Could not unmark this transfer.'));
      hapticError();
    } finally {
      setUnmarkingId(null);
    }
  }

  const dateRangeOn = manualDateFrom !== null || manualDateTo !== null;
  const filtersOn = (statusFilter !== 'ALL' ? 1 : 0) + (dateRangeOn ? 1 : 0) + (internationalOnly ? 1 : 0);
  // Blank while the first load runs and when it failed with nothing on screen. totalElements falls
  // back to 0 when there are no pages, so a cold failure would otherwise print a confident zero
  // directly above "Couldn't load your transactions." -- a request that failed is not an answer of
  // zero. A failed REFETCH keeps the previous pages and so still shows their real count.
  const countLabel = isLoading || (isError && txns.length === 0)
    ? ''
    : `${totalElements.toLocaleString('en-IN')} ${totalElements === 1 ? 'transaction' : 'transactions'}`;

  function onListScroll(e: NativeSyntheticEvent<NativeScrollEvent>) {
    const y = e.nativeEvent.contentOffset.y;
    // Setting the same value again does not render, so this costs a render only on a change.
    setBarSolid(y > PINNED_BAR_SOLID_AFTER);
    setBarCompact(y > titleHeight.current);
  }

  function focusSearch() {
    listRef.current?.scrollToOffset({ offset: 0, animated: true });
    searchRef.current?.focus();
  }

  // Clearing an arriving filter does not touch route.params (they belong to whichever screen
  // navigated in); it only resets this screen's own copy.
  function clearDrillThrough() {
    setActiveDrillThrough(null);
    // A keyword-only arrival (Insights' Top Merchant) has no OTHER field this clears -- without
    // this, the chip disappears (looking cleared) while the search box and the results stay
    // silently narrowed. Only when the box still holds the seeded, unedited value
    // (drillThroughKeyword is nulled the moment the person types over it): their own typing is
    // never cleared by this.
    if (drillThroughKeyword !== null) {
      setKeywordInput('');
      setDrillThroughKeyword(null);
    }
  }

  // One chip per filter that is on and not visible in the controls themselves. An arriving filter
  // is shown rather than silently applied: a narrowed list with nothing on screen explaining why
  // reads as "the list is broken".
  const chips: ActiveFilterChip[] = [];
  if (activeDrillThrough) chips.push({ key: 'arrived', label: activeDrillThrough.label, onClear: clearDrillThrough });
  if (statusFilter !== 'ALL') {
    chips.push({ key: 'status', label: statusFilterLabel(statusFilter), onClear: () => setStatusFilter('ALL') });
  }
  if (dateRangeOn) {
    const from = fmtDate(manualDateFrom);
    const to = fmtDate(manualDateTo);
    chips.push({
      key: 'dates',
      label: from && to ? `${from} to ${to}` : from ? `From ${from}` : `Until ${to}`,
      onClear: () => { setManualDateFrom(null); setManualDateTo(null); },
    });
  }
  if (internationalOnly) chips.push({ key: 'international', label: 'International', onClear: () => setInternationalOnly(false) });

  return (
    <GlassScreen style={styles.flex}>
      {/*
        One scrolling region: the title, the month card and the controls are the list's header, so
        they scroll away and the transactions take the screen. The list runs under the status bar;
        the pinned bar after it covers that area once the list has moved.
      */}
      <FlatList
        ref={listRef}
        testID="ledger-list"
        data={isLoading || (isError && txns.length === 0) ? [] : pieces}
        keyExtractor={(piece) => piece.key}
        // An item is a piece of a day, up to 25 rows, not one row: four pieces fill the first
        // screen on the account this was measured on, where the old twelve would now be up to 300
        // rows. No getItemLayout: a piece's height follows its row count and the text size.
        initialNumToRender={4}
        windowSize={9}
        removeClippedSubviews
        onScroll={onListScroll}
        scrollEventThrottle={16}
        onEndReached={() => {
          if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
        }}
        onEndReachedThreshold={0.4}
        refreshing={isFetching && !isFetchingNextPage}
        onRefresh={() => void refetch()}
        // The list runs under the status bar so the pinned bar can cover it; the spinner must not.
        progressViewOffset={insets.top}
        contentContainerStyle={[styles.listContent, { paddingTop: insets.top }]}
        ListHeaderComponent={
          <View style={styles.header}>
            <LedgerTitleBlock
              countLabel={countLabel}
              onAdd={() => setAddingTransaction(true)}
              onHeight={(height) => { titleHeight.current = height; }}
            />
            {summary ? (
              <LedgerMonthCard
                kpis={snapshotKpis}
                title={periodTitle}
                deltaLabel={deltaLabel}
                deltaSpokenLabel={deltaSpokenLabel}
                stacked={largeText}
              />
            ) : null}
            <LedgerControls
              keyword={keywordInput}
              onKeywordChange={(text) => {
                setKeywordInput(text);
                // Real typing supersedes a keyword that arrived from another screen the moment it
                // happens -- same "wins until superseded" shape as the manual date range.
                setDrillThroughKeyword(null);
              }}
              searchRef={searchRef}
              typeFilter={typeFilter}
              onTypeFilter={setTypeFilter}
              filtersOn={filtersOn}
              onOpenFilters={() => setFiltersOpen(true)}
              chips={chips}
              stacked={largeText}
            />
            {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
          </View>
        }
        ListEmptyComponent={
          <View testID="ledger-state-card" style={[styles.stateCard, glassRow]}>
            {isLoading ? (
              Array.from({ length: 8 }).map((_, i) => <SkeletonTransactionRow key={i} />)
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
              <View style={styles.state}>
                <Text style={[styles.errorText, { color: c.muted }]}>Couldn't load your transactions.</Text>
                <Pressable onPress={() => void refetch()} style={styles.retryButton} accessibilityRole="button">
                  <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
                </Pressable>
              </View>
            ) : (
              <Text style={[styles.empty, { color: c.muted }]}>
                {debouncedKeyword || typeFilter !== 'ALL' || statusFilter !== 'ALL' || internationalOnly || activeDrillThrough
                  || manualDateFrom || manualDateTo
                  ? 'No transactions match these filters.'
                  : 'No transactions yet. Import a statement to get started.'}
              </Text>
            )}
          </View>
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
              <Pressable onPress={() => void fetchNextPage()} style={styles.retryButton} accessibilityRole="button">
                <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
              </Pressable>
            </View>
          ) : undefined
        }
        renderItem={({ item }) => (
          <LedgerDayCard
            piece={item}
            deletingId={deletingId}
            largeText={largeText}
            onOpen={setViewingDetail}
            onDelete={confirmDelete}
          />
        )}
      />

      <LedgerPinnedBar
        solid={barSolid}
        compact={barCompact}
        topInset={insets.top}
        onSearch={focusSearch}
        onAdd={() => setAddingTransaction(true)}
      />

      {filtersOpen ? (
        <LedgerFiltersSheet
          status={statusFilter}
          onStatus={setStatusFilter}
          dateFrom={manualDateFrom}
          dateTo={manualDateTo}
          onDateFrom={setManualDateFrom}
          onDateTo={setManualDateTo}
          internationalOnly={internationalOnly}
          onInternational={setInternationalOnly}
          onClearAll={() => {
            setStatusFilter('ALL');
            setManualDateFrom(null);
            setManualDateTo(null);
            setInternationalOnly(false);
          }}
          // The live count, or nothing while it is loading or the request failed.
          resultCount={isFetching || isError ? null : totalElements}
          onClose={() => setFiltersOpen(false)}
        />
      ) : null}

      <TransactionSourceModal transactionId={viewingSourceId} onClose={() => setViewingSourceId(null)} />

      {/* Bug found in review: without a `key` this is a single persistent instance reused across
          every transaction, so its local marking/error state would survive from one viewed
          transaction into the next. Keying by transaction forces a clean remount on every open.
          Prefixed, not the bare transaction id: BankCorrectionModal below is a sibling keyed the
          same way, and two bare keys were equal whenever both modals were closed. */}
      <MarkTransferModal
        key={`mark-transfer-${markingTransfer?.id ?? 'none'}`}
        transaction={markingTransfer}
        onClose={() => setMarkingTransfer(null)}
        onMarked={() => { setMarkingTransfer(null); invalidateFinancialData(queryClient); }}
      />

      <TransactionExplanationModal
        transactionId={explaining?.id ?? null}
        category={explaining?.category ?? null}
        showCountsAs={explaining?.credit ?? false}
        onClose={() => setExplaining(null)}
      />

      {/* Bug found in review: without a `key` here, this component is a single persistent
          instance reused across every transaction (it's given no key elsewhere in this file
          either, matching MarkTransferModal's own shape) -- its local acknowledging/error state
          would otherwise survive from one viewed transaction into the next. Keying by which
          transaction is being viewed forces a clean remount (fresh state) on every open, the
          same fix React's own docs recommend over resetting state manually inside an effect. */}
      <BankCorrectionModal
        key={`bank-correction-${viewingCorrection?.id ?? 'none'}`}
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
          blank slate -- the user is correcting an answer, not supplying a missing one.
          CategoryPickerModal, not OptionPickerModal: recategorizing a row is exactly when a user
          is most likely to want a category that doesn't exist yet -- same gap as
          CategoryReviewScreen's identical fix. */}
      <CategoryPickerModal
        visible={recategorizing !== null}
        title="Change category"
        selectedName={recategorizing?.categoryName ?? null}
        onSelect={(category) => {
          const target = recategorizing;
          if (target) void applyCategory(target, category.name);
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
            setExplaining({ id: viewingDetail.id, category: viewingDetail.categoryName, credit: viewingDetail.type !== 'EXPENSE' });
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
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  listContent: { paddingHorizontal: spacing.ml, paddingBottom: spacing.xl },
  header: { gap: spacing.ms },
  error: { fontSize: 13 },
  // The loading, failed and empty states sit in one card, where a day's card would be.
  stateCard: {
    borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.xxl,
    paddingHorizontal: spacing.md, paddingVertical: spacing.xs, marginTop: spacing.md,
  },
  state: { alignItems: 'center', paddingVertical: spacing.lg },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.xl },
  footer: { paddingVertical: spacing.md, alignItems: 'center', gap: spacing.xs },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
  // Design review (Apple HIG / touch-target guidance): a bare 14pt text label with hitSlop 12
  // fell short of the 44pt minimum -- hitSlop pads the text's own rendered height, it doesn't
  // guarantee one. An explicit minHeight does.
  retryButton: { minHeight: 44, justifyContent: 'center' },
});
