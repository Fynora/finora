import { useState } from 'react';
import {
  ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { CategoryPickerModal } from '../components/CategoryPickerModal';
import { MerchantLogo } from '../components/MerchantLogo';
import { QuickSortPanel } from '../components/QuickSortPanel';
import { SkeletonGroup, SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { categoriesApi, transactionsApi } from '../api/endpoints';
import { useToast } from '../context/ToastContext';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { fmtCurrency, fmtDate } from '../lib/format';
import { rowTitle } from '../lib/ledgerRows';
import { askCategoryScope } from '../lib/askCategoryScope';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { spacing, useTheme } from '../theme';
import type { CounterpartyGroup, MerchantGroup, Transaction } from '../types';
import { GlassScreen } from '../components/GlassScreen';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';

/**
 * The categorization correction loop — the mobile half of "Ask Once, Learn Forever".
 *
 * Mobile shipped `needsReview`/`updateCategory`/`bulkRecategorize` in its API layer with zero
 * callers, and Settings promised a queue ("anything below it is left for you to confirm") that had
 * nowhere to lead. This is that queue.
 *
 * Two sections, because the server splits the backlog into two disjoint halves and rendering only
 * one strands the other (see `lib/reviewQueue.ts`). Merchants come first deliberately: the design
 * spec calls merchant review — not transaction review — the primary interaction, ordered by how
 * many transactions each correction clears, which is exactly the order the server already returns
 * groups in. Labelling "Swiggy" once should clear five rows, and be the first thing offered.
 *
 * Not a port of the web's two Dashboard cards. Those use a select-then-confirm pair per row, which
 * costs two taps and a lot of vertical space on a phone; here the picker sheet *is* the
 * confirmation — you cannot open it by accident and you cannot leave it having chosen nothing.
 */
export function CategoryReviewScreen() {
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const largeText = useLargeFontScale();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const [target, setTarget] = useState<PickerTarget | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  // Person groups whose payments are shown in the row.
  const [openCounterparties, setOpenCounterparties] = useState(() => new Set<string>());
  // Rows the user has already resolved this visit, HIDDEN rather than removed from the query
  // cache -- see apply() for why that distinction is the whole fix.
  const [resolvedTxnIds, setResolvedTxnIds] = useState(() => new Set());
  const [resolvedMerchantIds, setResolvedMerchantIds] = useState(() => new Set());
  // Phase 4. Same hide-not-remove reasoning as resolvedMerchantIds above, keyed by
  // counterpartyKey since that's this group's own identity, not a merchantId.
  const [resolvedCounterpartyKeys, setResolvedCounterpartyKeys] = useState(() => new Set());
  // Quick sort first: while its first batch has questions, the full lists below wait behind "See
  // all waiting payments". With none (or a failed load) the screen is exactly as it was, so every
  // waiting row stays reachable either way.
  const [quickSortQuestions, setQuickSortQuestions] = useState(0);
  const [showAllWaiting, setShowAllWaiting] = useState(false);
  const listsBehindQuickSort = quickSortQuestions > 0 && !showAllWaiting;

  const singlesQ = useQuery({
    queryKey: ['needs-review'],
    queryFn: () => transactionsApi.needsReview(),
  });
  const groupsQ = useQuery({
    queryKey: ['needs-review-groups'],
    queryFn: () => transactionsApi.needsReviewGroups(),
  });
  // Phase 4. Disjoint from BOTH queries above -- a merchant-matched row never reaches this
  // grouping (TransactionGroupingService's own doc), so all three partition the backlog rather
  // than double-surfacing a row.
  const counterpartyQ = useQuery({
    queryKey: ['needs-review-by-counterparty'],
    queryFn: () => transactionsApi.needsReviewByCounterparty(),
  });
  // Not read directly here any more -- CategoryPickerModal below fetches this same ['categories']
  // key itself. Kept only so refresh() can still recover a failed load: TanStack Query shares one
  // cache entry per key, so refetching it here repairs what the picker sees too.
  const categoriesQ = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000, // the category list barely changes within a session
  });

  const singles = (singlesQ.data ?? []).filter((t) => !resolvedTxnIds.has(t.id));
  const groups = (groupsQ.data ?? []).filter((g) => !resolvedMerchantIds.has(g.merchantId));
  const counterpartyGroups = (counterpartyQ.data ?? [])
    .filter((g) => !resolvedCounterpartyKeys.has(g.counterpartyKey));
  const loading = singlesQ.isLoading || groupsQ.isLoading || counterpartyQ.isLoading;
  // All three down AND nothing left to show. The `.length === 0` half is not redundant: in
  // TanStack v5 a query KEEPS its data and flips to status 'error' when a *background* refetch
  // fails, so deriving this from isError alone replaced a full, perfectly actionable queue with
  // an error card the moment a refresh blipped -- losing the user's place mid-way through it.
  // LedgerScreen.tsx guards the identical case as `isError && txns.length === 0`; this screen
  // shipped without the second half.
  const failed = singlesQ.isError && groupsQ.isError && counterpartyQ.isError
    && singles.length === 0 && groups.length === 0 && counterpartyGroups.length === 0;
  // At least one third down, but not all. The rows that loaded stay fully usable, but the queue on
  // screen is incomplete and saying nothing about that would quietly understate the user's backlog.
  const partiallyFailed = !failed && (singlesQ.isError || groupsQ.isError || counterpartyQ.isError);

  async function refresh() {
    setRefreshing(true);
    try {
      // categoriesQ included deliberately: it is the query whose failure makes the picker open
      // with nothing in it, so leaving it out of the recovery path made "Try again" unable to
      // fix the one thing most likely to be broken.
      await Promise.all([singlesQ.refetch(), groupsQ.refetch(), counterpartyQ.refetch(), categoriesQ.refetch()]);
    } finally {
      setRefreshing(false);
    }
  }

  /**
   * Apply a category, removing the row optimistically.
   *
   * The queue exists to be worked through quickly, so waiting on each round trip before the row
   * clears is the whole cost of doing this one-by-one. Correctness still lives in the response:
   * both failure paths below put the row back exactly where it was, and the cache invalidation on
   * success replaces the guess with the server's real answer.
   */
  async function apply(categoryName: string) {
    if (!target) return;
    const chosen = target;
    setTarget(null);

    // Resolved rows are hidden via `resolved*Ids`, NOT removed with queryClient.setQueryData.
    //
    // The cache-mutating version had a race with its own success path: this screen's queue lives
    // under 'needs-review'/'needs-review-groups', and invalidateFinancialData refetches exactly
    // those keys. Resolve row A, then row B before A's request lands; A succeeds and invalidates;
    // the refetch runs while B's write is still in flight, so the server still reports B as
    // needing review and B pops back onto the screen as uncategorized. The user then re-answers a
    // row they already answered, and the two writes race. The failure rollback made it worse --
    // it re-inserted a row the refetch had already restored, giving two rows with one React key.
    //
    // Hiding sidesteps all of it: the cache stays the server's to own, so a refetch landing at any
    // moment cannot contradict the UI, and rollback is just un-hiding -- which restores the row to
    // its original position for free, with no index arithmetic to get wrong.
    //
    // Still deliberately NOT wrapped in useSingleFlight: each row is its own action, and
    // serializing them would silently discard a correction to row B while row A was in flight.
    // Concurrency is the point here, which is precisely why the race above had to be closed
    // properly rather than by re-serializing.
    if (chosen.kind === 'single') {
      const id = chosen.txn.id;
      // Asked before the row is hidden: it stays on screen while the question is open. Other rows
      // from the payee that SIMILAR changes leave with the refetch invalidateFinancialData starts.
      const scope = await askCategoryScope(id);
      if (scope === null) return;
      setResolvedTxnIds((prev) => new Set(prev).add(id));
      const startedAt = requestStartedAt();
      try {
        await transactionsApi.updateCategory(id, categoryName, scope);
        hapticSuccess();
        showToast(`Saved ${categoryName} for ${rowTitle(chosen.txn)}`);
        invalidateFinancialData(queryClient);
      } catch (e) {
        reportTransportFailure(e, 'category-review:single', startedAt);
        setResolvedTxnIds((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        showToast(toUserMessage(e, 'Could not save that category.'), undefined, 'error');
        hapticError();
      }
      return;
    }

    if (chosen.kind === 'group') {
      const merchantId = chosen.group.merchantId;
      setResolvedMerchantIds((prev) => new Set(prev).add(merchantId));
      const startedAt = requestStartedAt();
      try {
        await transactionsApi.bulkRecategorize(chosen.group.transactionIds, categoryName);
        hapticSuccess();
        showToast(`Saved ${categoryName} for ${chosen.group.merchantName}`, transactionCount(chosen.group.transactionIds.length));
        invalidateFinancialData(queryClient);
      } catch (e) {
        reportTransportFailure(e, 'category-review:merchant-group', startedAt);
        setResolvedMerchantIds((prev) => {
          const next = new Set(prev);
          next.delete(merchantId);
          return next;
        });
        showToast(toUserMessage(e, 'Could not apply that category.'), undefined, 'error');
        hapticError();
      }
      return;
    }

    // Phase 4 -- same bulkRecategorize write path as the merchant group above, keyed by
    // counterpartyKey instead of merchantId since that's this group's own identity.
    const counterpartyKey = chosen.group.counterpartyKey;
    setResolvedCounterpartyKeys((prev) => new Set(prev).add(counterpartyKey));
    const startedAt = requestStartedAt();
    try {
      await transactionsApi.bulkRecategorize(chosen.group.transactionIds, categoryName);
      hapticSuccess();
      showToast(`Saved ${categoryName} for ${chosen.group.label}`, transactionCount(chosen.group.transactionIds.length));
      invalidateFinancialData(queryClient);
    } catch (e) {
      reportTransportFailure(e, 'category-review:counterparty-group', startedAt);
      setResolvedCounterpartyKeys((prev) => {
        const next = new Set(prev);
        next.delete(counterpartyKey);
        return next;
      });
      showToast(toUserMessage(e, 'Could not apply that category.'), undefined, 'error');
      hapticError();
    }
  }

  // Both halves must have SUCCEEDED before the app tells someone their queue is clear. Deriving
  // this from "no rows and not fully failed" instead conflates a failure with a genuine zero --
  // one half erroring while the other returned nothing would congratulate the user for finishing
  // work they can't currently see. That is the same class of bug LedgerScreen.test.tsx exists to
  // pin ("a failed request and an empty one do not render the same thing"), reached from a
  // different direction.
  const empty = singlesQ.isSuccess && groupsQ.isSuccess && counterpartyQ.isSuccess
    && singles.length === 0 && groups.length === 0 && counterpartyGroups.length === 0;

  // Payments still waiting across the three lists, for the link that opens them.
  const waitingPayments = singles.length
    + groups.reduce((n, g) => n + g.transactionIds.length, 0)
    + counterpartyGroups.reduce((n, g) => n + g.transactionIds.length, 0);

  function toggleCounterparty(key: string) {
    setOpenCounterparties((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  // A logo, as the Ledger's rows have. Left out at large text sizes, as there, to give the words
  // the width. Only the server's `logoDomain` is ever sent to Logo.dev (see MerchantLogo); `name`
  // is shown as initials.
  const logo = (name: string, domain: string | null | undefined) => (largeText ? null : (
    <MerchantLogo merchant={name || '?'} domain={domain ?? null} size={LOGO_SIZE} style={styles.logo} />
  ));
  const chevron = <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />;

  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView
      contentContainerStyle={padForTabBar([styles.content, { paddingTop: insets.top + spacing.md }], tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}
      refreshControl={<AppRefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <Text style={[styles.title, { color: c.ink }]}>Review categories</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Pick a category once — Fynora remembers it for every future transaction from the same
        merchant.
      </Text>

      <QuickSortPanel onLoaded={setQuickSortQuestions} />

      {listsBehindQuickSort ? (
        <Pressable onPress={() => setShowAllWaiting(true)} hitSlop={12} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>{seeAllLabel(waitingPayments)}</Text>
        </Pressable>
      ) : null}
      {!listsBehindQuickSort && partiallyFailed ? (
        <Text style={[styles.warning, { color: c.mutedInk }]}>
          Part of your review queue couldn’t be loaded. Pull down to try again.
        </Text>
      ) : null}

      {listsBehindQuickSort ? null : loading ? (
        <Card style={styles.section}>
          <SkeletonGroup>
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
            <SkeletonTransactionRow />
          </SkeletonGroup>
        </Card>
      ) : failed ? (
        <Card style={styles.section}>
          <Text style={[styles.error, { color: c.dangerInk }]}>Couldn’t load your review queue.</Text>
          <Pressable onPress={refresh} hitSlop={12} accessibilityRole="button">
            <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
          </Pressable>
        </Card>
      ) : empty ? (
        <Card style={styles.section}>
          <EmptyState message="Nothing to review — every transaction has a category." />
        </Card>
      ) : (
        <>
          {groups.length > 0 ? (
            <Card style={styles.section}>
              <SectionHeading title={`Categorize a whole merchant · ${groups.length}`} />
              <Text style={[styles.sectionHint, { color: c.muted }]}>
                One choice covers every transaction from that merchant.
              </Text>
              {groups.map((g) => (
                <Pressable
                  key={g.merchantId}
                  onPress={() => setTarget({ kind: 'group', group: g })}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  android_ripple={{ color: c.border }}
                  accessibilityRole="button"
                  accessibilityLabel={`${g.merchantName}, ${g.transactionIds.length} transactions`}
                  accessibilityHint="Opens the category picker for every transaction from this merchant"
                >
                  {logo(g.merchantName, g.logoDomain)}
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                      {g.merchantName}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                      {g.transactionIds.length} transactions
                    </Text>
                  </View>
                  {chevron}
                </Pressable>
              ))}
            </Card>
          ) : null}

          {/* Phase 4. "Paid a Person" et al -- money tied up with one counterparty across several
              transactions, exactly the merchant-group case above but grouped by WHO instead of
              WHAT. Disjoint from the merchant section (a merchant-matched row never reaches this
              grouping), so this is a third bucket, not a re-slice of the same rows. Ordered by
              totalValue server-side -- the biggest amounts first, same principle as merchant
              groups being ordered by row count. */}
          {counterpartyGroups.length > 0 ? (
            <Card style={styles.section}>
              <SectionHeading title={`Categorize by who you paid · ${counterpartyGroups.length}`} />
              <Text style={[styles.sectionHint, { color: c.muted }]}>
                One choice covers every transaction with that person or business.
              </Text>
              {counterpartyGroups.map((g) => (
                // The row's button and "Show payments" are siblings, not nested: an accessible
                // element is read as one, so inside the row's button "Show payments" and the list
                // it opens were out of a screen reader's reach.
                <View key={g.counterpartyKey} style={[styles.rowGroup, { borderBottomColor: c.border }]}>
                  <Pressable
                    onPress={() => setTarget({ kind: 'counterparty', group: g })}
                    style={styles.rowInGroup}
                    android_ripple={{ color: c.border }}
                    accessibilityRole="button"
                    accessibilityLabel={`${g.label}, ${g.counterpartyType === 'PERSON' ? 'person' : 'business'}${
                      g.identityIsStrong ? '' : ', probable match'
                    }, ${fmtCurrency(g.totalValue)} across ${g.transactionIds.length} transactions`}
                    accessibilityHint="Opens the category picker for every transaction with this counterparty"
                  >
                    {/* The label is the latest row's bank narration (TransactionGroupingService), not a name. */}
                    {logo(g.label, null)}
                    <View style={styles.rowMain}>
                      <View style={styles.counterpartyTitleRow}>
                        <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                          {g.label}
                        </Text>
                        {/* Context, not a resolved identity -- same reasoning as the row-level
                            counterparty label elsewhere in this app. No direction composed in (a
                            group can carry both sent and received rows), so this shows only the
                            noun, never "sent to"/"paid". */}
                        <Text style={[styles.counterpartyTypeBadge, { color: c.ink, backgroundColor: c.border }]}>
                          {g.counterpartyType === 'PERSON' ? 'Person' : 'Business'}
                        </Text>
                        {/* A name: key is a guess -- must never be presented as a resolved identity.
                            This group still gets the bulk-apply action (grouping survives a guessed
                            key), but is visibly marked probable rather than confirmed. */}
                        {!g.identityIsStrong ? (
                          <Text style={[styles.counterpartyTypeBadge, { color: c.warningInk, backgroundColor: c.warningBg }]}>
                            Probable
                          </Text>
                        ) : null}
                      </View>
                      <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                        {fmtCurrency(g.totalValue)} · {g.transactionIds.length} transactions
                      </Text>
                    </View>
                    {chevron}
                  </Pressable>
                  {/* Lined up under the title, past the logo when there is one. */}
                  <View style={{ marginLeft: largeText ? 0 : LOGO_SIZE + spacing.sm }}>
                    {/* The payments themselves, which the group already carries, so they can be
                        seen before one category is chosen for all of them. Its own button: a tap
                        here opens the list, not the picker. */}
                    {g.transactions.length > 0 ? (
                      <Pressable
                        onPress={() => toggleCounterparty(g.counterpartyKey)}
                        hitSlop={8}
                        accessibilityRole="button"
                        accessibilityState={{ expanded: openCounterparties.has(g.counterpartyKey) }}
                        style={styles.showPayments}
                      >
                        <Text style={[styles.showPaymentsText, { color: c.primary }]}>
                          {openCounterparties.has(g.counterpartyKey)
                            ? 'Hide payments'
                            : `Show ${g.transactions.length} ${g.transactions.length === 1 ? 'payment' : 'payments'}`}
                        </Text>
                      </Pressable>
                    ) : null}
                    {openCounterparties.has(g.counterpartyKey) ? (
                      <View style={styles.payments}>
                        {g.transactions.map((p) => (
                          <Text key={p.id} style={[styles.rowMeta, { color: c.mutedInk }]}>
                            {fmtDate(p.date) ?? p.date} · {signedAmount(p)}
                          </Text>
                        ))}
                        {g.transactionIds.length > g.transactions.length ? (
                          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
                            and {g.transactionIds.length - g.transactions.length} more
                          </Text>
                        ) : null}
                      </View>
                    ) : null}
                  </View>
                </View>
              ))}
            </Card>
          ) : null}

          {singles.length > 0 ? (
            <Card style={styles.section}>
              <SectionHeading title={`One-off transactions · ${singles.length}`} />
              <Text style={[styles.sectionHint, { color: c.muted }]}>
                These don’t share a merchant with anything else waiting.
              </Text>
              {singles.map((t) => (
                <Pressable
                  key={t.id}
                  onPress={() => setTarget({ kind: 'single', txn: t })}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  android_ripple={{ color: c.border }}
                  accessibilityRole="button"
                  accessibilityLabel={`${rowTitle(t)}, ${t.type === 'INCOME' ? 'received' : 'spent'} ${fmtCurrency(
                    Math.abs(t.amount)
                  )}, ${fmtDate(t.date) ?? t.date}${t.categoryName ? `, now ${t.categoryName}` : ''}`}
                  accessibilityHint="Opens the category picker for this transaction"
                >
                  {logo(t.merchant || t.description, t.logoDomain)}
                  {/* Titled, dated and signed as the Ledger's rows are (rowTitle, LedgerDayCard):
                      the bank's raw narration and an ISO date read as noise, and without a sign a
                      salary credit looked exactly like a purchase. */}
                  <View style={styles.rowMain}>
                    <Text style={[styles.rowTitle, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                      {rowTitle(t)}
                    </Text>
                    <Text style={[styles.rowMeta, { color: c.mutedInk }]} numberOfLines={largeText ? 2 : 1}>
                      {fmtDate(t.date) ?? t.date}{t.categoryName ? ` · Now: ${t.categoryName}` : ''}
                    </Text>
                    {/* Under the title at large text, as the Ledger puts it, so the title keeps
                        the width. */}
                    {largeText ? (
                      <View testID={`review-amount-below-${t.id}`} style={styles.amountBelow}>
                        <Text style={[styles.amount, { color: t.type === 'INCOME' ? c.successInk : c.dangerInk }]}>
                          {signedAmount(t)}
                        </Text>
                      </View>
                    ) : null}
                  </View>
                  {largeText ? null : (
                    <Text style={[styles.amount, { color: t.type === 'INCOME' ? c.successInk : c.dangerInk }]}>
                      {signedAmount(t)}
                    </Text>
                  )}
                  {chevron}
                </Pressable>
              ))}
            </Card>
          ) : null}
        </>
      )}

      {/* Rendered unconditionally so the sheet's slide-out animation isn't cut off by the row
          that opened it disappearing optimistically the moment a category is picked.
          CategoryPickerModal, not OptionPickerModal: this queue is exactly where a flagged
          merchant or transaction is most likely to need a category that doesn't exist yet, and
          OptionPickerModal (a bare fixed list) offered no way to create one -- the review flow
          was the one place in the app category creation was unreachable. */}
      <CategoryPickerModal
        visible={target !== null}
        title={pickerTitle(target)}
        // The pick saves at once; apply's success or error is its one haptic.
        silent
        // A one-off opens on the category it is filed as now, so confirming a right guess is one
        // tap. A group's rows may sit under different categories, so it opens on none.
        selectedName={target?.kind === 'single' ? target.txn.categoryName || null : null}
        onSelect={(category) => void apply(category.name)}
        onClose={() => setTarget(null)}
      />

      {singlesQ.isFetching || groupsQ.isFetching || counterpartyQ.isFetching ? (
        <View style={styles.footerSpinner}>
          <ActivityIndicator size="small" color={c.muted} />
        </View>
      ) : null}
    </ScrollView>
    </GlassScreen>
  );
}

type PickerTarget =
  | { kind: 'single'; txn: Transaction }
  | { kind: 'group'; group: MerchantGroup }
  | { kind: 'counterparty'; group: CounterpartyGroup };

/**
 * Names the stakes in the sheet's own header, because on mobile the row that explains them is
 * behind the sheet: "Apply to 5 Swiggy transactions" is a materially different action from
 * categorizing one row, and the user should see which one they're confirming.
 */
function pickerTitle(target: PickerTarget | null): string {
  if (target?.kind === 'group' || target?.kind === 'counterparty') {
    return `Apply to ${transactionCount(target.group.transactionIds.length)}`;
  }
  return 'Choose a category';
}

const LOGO_SIZE = 32;

function transactionCount(n: number): string {
  return `${n} ${n === 1 ? 'transaction' : 'transactions'}`;
}

/** Money out with a minus and money in with a plus, as the Ledger writes an amount. */
function signedAmount(t: { amount: number; type: 'INCOME' | 'EXPENSE' }): string {
  return `${t.type === 'INCOME' ? '+' : '-'}${fmtCurrency(Math.abs(t.amount))}`;
}

/** The link that opens the lists, with how many payments wait in them once that is known. */
function seeAllLabel(waiting: number): string {
  if (waiting === 0) return 'See all waiting payments';
  return waiting === 1 ? 'See the 1 waiting payment' : `See all ${waiting} waiting payments`;
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 4, marginBottom: spacing.md },
  section: { marginBottom: spacing.md },
  sectionHint: { fontSize: 12, marginBottom: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    // The smallest comfortable touch target.
    minHeight: 44,
    gap: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  // A person row: its button, then "Show payments" under it, with one rule below both.
  rowGroup: { borderBottomWidth: StyleSheet.hairlineWidth, paddingBottom: 12 },
  rowInGroup: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingTop: 12, minHeight: 44, gap: spacing.sm,
  },
  logo: { alignSelf: 'flex-start' },
  rowMain: { flex: 1 },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  amount: { fontSize: 14, fontWeight: '600' },
  amountBelow: { marginTop: 4 },
  counterpartyTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
  // 11pt: 9 was below the smallest size the app uses for anything a person has to read.
  counterpartyTypeBadge: {
    fontSize: 11, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.3,
    paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4, overflow: 'hidden',
  },
  showPayments: { alignSelf: 'flex-start', marginTop: 6 },
  showPaymentsText: { fontSize: 12, fontWeight: '600' },
  payments: { marginTop: 4 },
  error: { fontSize: 13, marginBottom: spacing.sm },
  warning: { fontSize: 12, marginBottom: spacing.sm },
  retry: { fontSize: 13, fontWeight: '600' },
  footerSpinner: { alignItems: 'center', paddingVertical: spacing.sm },
});
