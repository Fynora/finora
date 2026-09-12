import { useState } from 'react';
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Card, EmptyState } from '../components/Card';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { SkeletonTransactionRow } from '../components/skeletons/Skeletons';
import { categoriesApi, gmailApi, type GmailReviewItem } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency, fromLocalDateString } from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { useKeyedSingleFlight } from '../lib/useSingleFlight';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { spacing, useTheme } from '../theme';

function confidenceLabel(confidence: number | null): string | null {
  if (confidence === null) return null;
  return `${Math.round(confidence * 100)}% confidence`;
}

/**
 * Mobile counterpart to frontend/src/pages/GmailReview.tsx -- a true per-receipt review queue,
 * not the generic "Continue previous import" list CSV/PDF sessions share (each Gmail-sourced
 * ImportSession is a single-row session, GmailStagingBridge's own contract). Structurally modeled
 * on CategoryReviewScreen (same optimistic-hide-not-remove pattern for resolved rows, same
 * OptionPickerModal for the category correction -- see that screen's own extensive comment on
 * why hiding, not removing from the query cache, is what closes a real race between two rows
 * resolved close together).
 *
 * Reached from Settings' Connected Apps card, which is also where "Needs Review" is counted --
 * approve/reject both invalidate ['gmail-status'] so that count updates without the user leaving
 * this screen. approve() additionally invalidates the financial-data keys (a approved receipt
 * becomes a real transaction, unlike web's own approve(), which does not -- ledger/dashboard
 * screens elsewhere in this app all invalidate after any action that adds a transaction, and this
 * is exactly that).
 */
export function GmailReviewScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const largeText = useLargeFontScale();
  const queryClient = useQueryClient();

  const keyedSingleFlight = useKeyedSingleFlight();
  const [categoryTarget, setCategoryTarget] = useState<GmailReviewItem | null>(null);
  const [editedCategory, setEditedCategory] = useState<Record<string, string>>({});
  // A Set, not a single id -- each row is its own independent action (same reasoning as
  // CategoryReviewScreen's resolvedTxnIds/resolvedMerchantIds). A single busyId here used to make
  // approving/rejecting a second row while the first was still in flight incorrectly re-enable the
  // first row's buttons (busyId had moved on to the second row's sessionId), opening a real
  // double-submit window on the first row's own request. This state alone still isn't the actual
  // guard against a genuine same-frame double-tap on ONE row, though -- see keyedSingleFlight above
  // for that (useSingleFlight.ts's own doc comment on why state alone can't close that window: two
  // taps in the same frame both read the pre-update value).
  const [busySessionIds, setBusySessionIds] = useState(() => new Set<string>());
  const [rowError, setRowError] = useState<Record<string, string>>({});
  const [refreshing, setRefreshing] = useState(false);
  // Hidden, not removed from the query cache -- see this screen's own doc comment.
  const [resolvedIds, setResolvedIds] = useState(() => new Set<string>());

  const itemsQ = useQuery({
    queryKey: ['gmail-review-queue'],
    queryFn: () => gmailApi.reviewQueue(),
  });
  const categoriesQ = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000,
  });
  const categoryNames = (categoriesQ.data ?? []).map((x) => x.name);

  const items = (itemsQ.data ?? []).filter((i) => !resolvedIds.has(i.sessionId));

  async function refresh() {
    setRefreshing(true);
    try {
      await itemsQ.refetch();
    } finally {
      setRefreshing(false);
    }
  }

  function afterResolved() {
    void queryClient.invalidateQueries({ queryKey: ['gmail-status'] });
  }

  function setRowBusy(sessionId: string, busy: boolean) {
    setBusySessionIds((prev) => {
      const next = new Set(prev);
      if (busy) next.add(sessionId); else next.delete(sessionId);
      return next;
    });
  }

  async function approve(item: GmailReviewItem) {
    setRowError((prev) => ({ ...prev, [item.sessionId]: '' }));
    await keyedSingleFlight(item.sessionId, async () => {
      setRowBusy(item.sessionId, true);
      try {
        const category = editedCategory[item.sessionId];
        await gmailApi.approve(item.sessionId, category && category !== item.category ? category : undefined);
        setResolvedIds((prev) => new Set(prev).add(item.sessionId));
        hapticSuccess();
        invalidateFinancialData(queryClient);
        afterResolved();
      } catch (e) {
        setRowError((prev) => ({ ...prev, [item.sessionId]: toUserMessage(e, "Couldn't approve this receipt -- try again.") }));
        hapticError();
      } finally {
        setRowBusy(item.sessionId, false);
      }
    });
  }

  async function reject(item: GmailReviewItem) {
    setRowError((prev) => ({ ...prev, [item.sessionId]: '' }));
    await keyedSingleFlight(item.sessionId, async () => {
      setRowBusy(item.sessionId, true);
      try {
        await gmailApi.reject(item.sessionId);
        setResolvedIds((prev) => new Set(prev).add(item.sessionId));
        hapticSuccess();
        afterResolved();
      } catch (e) {
        setRowError((prev) => ({ ...prev, [item.sessionId]: toUserMessage(e, "Couldn't discard this receipt -- try again.") }));
        hapticError();
      } finally {
        setRowBusy(item.sessionId, false);
      }
    });
  }

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={c.primary} />}
    >
      <Text style={[styles.title, { color: c.ink }]}>Gmail Transactions</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Receipts Fynora found in your inbox. Nothing here is added to your ledger until you approve it.
      </Text>

      {itemsQ.isLoading ? (
        <Card style={styles.section}>
          <SkeletonTransactionRow />
          <SkeletonTransactionRow />
        </Card>
      ) : itemsQ.isError && items.length === 0 ? (
        <Card style={styles.section}>
          <Text style={[styles.error, { color: c.danger }]}>Couldn&apos;t load your Gmail receipts — please try again later.</Text>
          <Pressable onPress={() => void refresh()} hitSlop={12} accessibilityRole="button">
            <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
          </Pressable>
        </Card>
      ) : items.length === 0 ? (
        <Card style={styles.section}>
          <EmptyState message="Nothing waiting for review right now." />
        </Card>
      ) : (
        items.map((item) => {
          const category = editedCategory[item.sessionId] ?? item.category;
          const busy = busySessionIds.has(item.sessionId);
          return (
            <Card key={item.sessionId} style={styles.section}>
              <View style={styles.row}>
                <View style={styles.rowMain}>
                  <Text style={[styles.merchant, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>{item.merchant}</Text>
                  <Text style={[styles.meta, { color: c.mutedInk }]}>
                    {fromLocalDateString(item.date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}
                  </Text>
                </View>
                <View style={styles.amountCol}>
                  <Text style={[styles.amount, { color: c.ink }]}>{fmtCurrency(item.amount)}</Text>
                  {confidenceLabel(item.confidence) ? (
                    <Text style={[styles.confidence, { color: c.muted }]}>{confidenceLabel(item.confidence)}</Text>
                  ) : null}
                </View>
              </View>

              {item.reasoning ? (
                <Text style={[styles.reasoning, { color: c.muted }]}>{item.reasoning}</Text>
              ) : null}

              <Text style={[styles.categoryLabel, { color: c.muted }]}>Category</Text>
              <Pressable
                onPress={() => setCategoryTarget(item)}
                style={[styles.categoryPicker, { borderColor: c.border }]}
                accessibilityRole="button"
                accessibilityLabel={`Category: ${category}`}
              >
                <Text style={[styles.categoryValue, { color: c.ink }]}>{category}</Text>
              </Pressable>

              {rowError[item.sessionId] ? (
                <Text style={[styles.error, { color: c.danger }]}>{rowError[item.sessionId]}</Text>
              ) : null}

              <View style={[styles.actions, { borderTopColor: c.border }]}>
                {busy ? (
                  <ActivityIndicator color={c.primary} />
                ) : (
                  <>
                    <Pressable
                      onPress={() => void approve(item)}
                      style={[styles.actionButton, { backgroundColor: c.primary }]}
                      accessibilityRole="button"
                      accessibilityLabel={`Approve ${item.merchant}`}
                    >
                      <Text style={[styles.actionButtonText, { color: c.onPrimary }]}>Approve</Text>
                    </Pressable>
                    <Pressable
                      onPress={() => void reject(item)}
                      style={[styles.actionButton, styles.rejectButton, { borderColor: c.border }]}
                      accessibilityRole="button"
                      accessibilityLabel={`Reject ${item.merchant}`}
                    >
                      <Text style={[styles.actionButtonText, { color: c.ink }]}>Reject</Text>
                    </Pressable>
                  </>
                )}
              </View>
            </Card>
          );
        })
      )}

      {/* Rendered unconditionally, same reasoning as CategoryReviewScreen's identical comment: the
          sheet's slide-out animation shouldn't be cut off by the row that opened it disappearing
          optimistically the moment a category is picked. */}
      <OptionPickerModal
        visible={categoryTarget !== null}
        title="Choose a category"
        options={categoryNames}
        selected={categoryTarget ? (editedCategory[categoryTarget.sessionId] ?? categoryTarget.category) : null}
        onSelect={(name) => {
          if (categoryTarget) {
            setEditedCategory((prev) => ({ ...prev, [categoryTarget.sessionId]: name }));
          }
          setCategoryTarget(null);
        }}
        onClose={() => setCategoryTarget(null)}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 4, marginBottom: spacing.md },
  section: { marginBottom: spacing.md },
  row: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
  rowMain: { flex: 1 },
  merchant: { fontSize: 15, fontWeight: '600' },
  meta: { fontSize: 12, marginTop: 2 },
  amountCol: { alignItems: 'flex-end' },
  amount: { fontSize: 15, fontWeight: '700' },
  confidence: { fontSize: 11, marginTop: 2 },
  reasoning: { fontSize: 12, fontStyle: 'italic', marginTop: spacing.sm },
  categoryLabel: { fontSize: 11, textTransform: 'uppercase', marginTop: spacing.sm, marginBottom: 4 },
  categoryPicker: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 12, paddingVertical: 10 },
  categoryValue: { fontSize: 14 },
  error: { fontSize: 12, marginTop: spacing.xs },
  retry: { fontSize: 13, fontWeight: '600', marginTop: spacing.xs },
  actions: {
    flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm,
    paddingTop: spacing.sm, borderTopWidth: StyleSheet.hairlineWidth,
  },
  actionButton: {
    flex: 1, borderRadius: 8, paddingVertical: 10, alignItems: 'center', justifyContent: 'center',
  },
  rejectButton: { backgroundColor: 'transparent', borderWidth: 1 },
  actionButtonText: { fontSize: 13, fontWeight: '600' },
});
