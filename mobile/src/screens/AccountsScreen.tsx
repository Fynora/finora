import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { accountsApi } from '../api/endpoints';
import { AccountFormSheet } from './AccountFormSheet';
import { Button } from '../components/Button';
import { Card, EmptyState } from '../components/Card';
import { toUserMessage } from '../lib/apiError';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { fmtCurrency, fmtDate } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';
import type { Account } from '../types';

/**
 * How long a revealed account number stays visible before hiding again -- a common banking UX
 * pattern, ported from frontend/src/pages/Setup.tsx's AUTO_REMASK_MS. Also re-masks on unmount,
 * which falls out of `revealed` living in component state.
 *
 * Worth being precise about what this protects: Finora never stores a true, full account number.
 * What's revealed is the already-masked value the bank's own export or the import pipeline
 * produced (e.g. "••••4802"), so this is shoulder-surfing hygiene on a shared screen, not a
 * security boundary.
 */
const AUTO_REMASK_MS = 8000;

const ACCOUNT_TYPE_LABEL: Record<string, string> = {
  SAVINGS: 'Savings',
  CREDIT_CARD: 'Credit Card',
  WALLET: 'Wallet',
  INVESTMENT: 'Investment',
};

export function AccountsScreen() {
  // SEC-17 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Balances render
  // unconditionally here, and the reveal toggle above (AUTO_REMASK_MS) exists specifically to make
  // the masked account number visible on demand -- the single most screenshot-attractive moment in
  // the app, and one Dashboard/StatementHistory's own usePreventScreenCapture() calls don't cover
  // since this is a separate screen.
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const remaskTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const [formTarget, setFormTarget] = useState<Account | 'new' | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: accounts = [], isLoading, isError } = useQuery({
    queryKey: ['accounts'],
    queryFn: () => accountsApi.list(),
  });

  function onFormSaved() {
    setFormTarget(null);
    invalidateFinancialData(queryClient);
  }

  // Matches web's own ConfirmDialog copy for the same action (Setup.tsx) rather than guessing at
  // what happens to this account's transactions afterward -- soft-deleting an account is a wider
  // change than this screen's own scope to characterize precisely.
  function confirmDelete(a: Account) {
    Alert.alert(
      'Delete this account?',
      `"${a.name}" will be removed. This can't be undone.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive', onPress: () => void deleteAccount(a.id) },
      ]
    );
  }

  async function deleteAccount(id: string) {
    setError(null);
    setDeletingId(id);
    try {
      await accountsApi.remove(id);
      invalidateFinancialData(queryClient);
    } catch (e) {
      setError(toUserMessage(e, 'Could not delete this account.'));
    } finally {
      setDeletingId(null);
    }
  }

  useEffect(() => {
    // Every pending timer is dropped when this screen unmounts -- combined with `revealed` living
    // only in state, that satisfies "mask again when the user leaves" with no extra code.
    const timers = remaskTimers.current;
    return () => timers.forEach(clearTimeout);
  }, []);

  function toggleRevealed(id: string) {
    setRevealed((prev) => {
      const next = new Set(prev);
      const existing = remaskTimers.current.get(id);
      if (existing) {
        clearTimeout(existing);
        remaskTimers.current.delete(id);
      }

      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
        const timer = setTimeout(() => {
          setRevealed((cur) => {
            const copy = new Set(cur);
            copy.delete(id);
            return copy;
          });
          remaskTimers.current.delete(id);
        }, AUTO_REMASK_MS);
        remaskTimers.current.set(id, timer);
      }
      return next;
    });
  }

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
    >
      <View style={styles.headerRow}>
        <Text style={[styles.title, { color: c.ink }]}>Accounts</Text>
        <Button label="Add Account" variant="link" onPress={() => setFormTarget('new')} />
      </View>

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

      {isError ? (
        <Text style={[styles.error, { color: c.danger }]}>Could not load accounts.</Text>
      ) : accounts.length === 0 ? (
        <Card>
          <EmptyState message="No accounts yet. Import a statement or add one manually to get started." />
        </Card>
      ) : (
        accounts.map((a) => {
          const isRevealed = revealed.has(a.id);
          const lastImported = fmtDate(a.lastImportedAt);
          return (
            <Card key={a.id} style={styles.accountCard}>
              <View style={styles.accountHeader}>
                <View style={[styles.bankBadge, { backgroundColor: a.bank?.colorHex || c.primary }]}>
                  {/* White assumes the badge is showing a real bank's own brand color, which is
                      reliably saturated enough for it -- but the fallback case (no bank color,
                      c.primary) needs the theme-correct text, same as everywhere else c.primary
                      is a background: in dark mode it's light paper, where white text vanishes. */}
                  <Text style={[styles.bankInitials, { color: a.bank?.colorHex ? '#fff' : c.onPrimary }]}>
                    {a.bank?.initials || '?'}
                  </Text>
                </View>
                <View style={styles.accountTitleBlock}>
                  <Text style={[styles.accountName, { color: c.ink }]} numberOfLines={1}>
                    {a.name}
                  </Text>
                  <Text style={[styles.accountType, { color: c.muted }]}>
                    {ACCOUNT_TYPE_LABEL[a.accountType] ?? a.accountType}
                    {a.bank?.shortName ? ` · ${a.bank.shortName}` : ''}
                  </Text>
                </View>
              </View>

              <Text style={[styles.balance, { color: c.ink }]}>{fmtCurrency(a.balance)}</Text>
              {a.accountType === 'CREDIT_CARD' && a.creditLimit ? (
                <Text style={[styles.detail, { color: c.muted }]}>
                  Limit {fmtCurrency(a.creditLimit)}
                </Text>
              ) : null}

              {a.accountNumberMasked ? (
                <Pressable
                  onPress={() => toggleRevealed(a.id)}
                  style={styles.numberRow}
                  hitSlop={12}
                  accessibilityRole="button"
                  accessibilityState={{ expanded: isRevealed }}
                  // Never announces the digits themselves -- the label describes the action, and
                  // the value is only read from the visible Text when actually revealed.
                  accessibilityLabel={
                    isRevealed ? 'Hide account number' : 'Show account number'
                  }
                  accessibilityHint={isRevealed ? 'Hides again automatically after 8 seconds' : undefined}
                >
                  <Text style={[styles.detail, { color: c.muted }]}>
                    {isRevealed ? a.accountNumberMasked : '•••• ••••'}
                  </Text>
                  <Text style={[styles.reveal, { color: c.primary }]}>
                    {isRevealed ? 'Hide' : 'Show'}
                  </Text>
                </Pressable>
              ) : null}

              {a.accountHolderName ? (
                <Text style={[styles.detail, { color: c.muted }]}>{a.accountHolderName}</Text>
              ) : null}

              <View style={[styles.statsRow, { borderTopColor: c.border }]}>
                <Text style={[styles.stat, { color: c.muted }]}>
                  {a.transactionsCount.toLocaleString('en-IN')} transactions
                </Text>
                <Text style={[styles.stat, { color: c.muted }]}>
                  {lastImported ? `Last import ${lastImported}` : 'Never imported'}
                </Text>
              </View>

              <View style={[styles.actionsRow, { borderTopColor: c.border }]}>
                {deletingId === a.id ? (
                  <ActivityIndicator size="small" color={c.muted} />
                ) : (
                  <>
                    <Pressable
                      onPress={() => setFormTarget(a)}
                      hitSlop={8}
                      accessibilityRole="button"
                      accessibilityLabel={`Edit ${a.name}`}
                    >
                      <Text style={[styles.actionText, { color: c.primary }]}>Edit</Text>
                    </Pressable>
                    <Pressable
                      onPress={() => confirmDelete(a)}
                      hitSlop={8}
                      accessibilityRole="button"
                      accessibilityLabel={`Delete ${a.name}`}
                    >
                      <Text style={[styles.actionText, { color: c.danger }]}>Delete</Text>
                    </Pressable>
                  </>
                )}
              </View>
            </Card>
          );
        })
      )}

      {/* A statement import remains the primary, recommended way an account gets onto this
          screen -- Fynora detects its details automatically, which a manual entry never has.
          This note flags the manual path as the fallback it is, not an equal alternative. */}
      <Text style={[styles.note, { color: c.muted }]}>
        Importing a statement creates and fills in an account automatically. Add one manually only
        for accounts you don&apos;t plan to import statements for.
      </Text>

      {formTarget ? (
        <AccountFormSheet
          account={formTarget === 'new' ? null : formTarget}
          onClose={() => setFormTarget(null)}
          onSaved={onFormSaved}
        />
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  headerRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm,
  },
  title: { fontSize: 22, fontWeight: '700' },
  error: { fontSize: 13, marginBottom: spacing.sm },
  accountCard: { marginBottom: spacing.sm },
  accountHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.sm },
  bankBadge: {
    width: 36,
    height: 36,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.sm,
  },
  bankInitials: { fontWeight: '700', fontSize: 13 },
  accountTitleBlock: { flex: 1 },
  accountName: { fontSize: 15, fontWeight: '600' },
  accountType: { fontSize: 11, marginTop: 1 },
  balance: { fontSize: 22, fontWeight: '700' },
  detail: { fontSize: 12, marginTop: 4 },
  numberRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: 4 },
  reveal: { fontSize: 12, fontWeight: '600' },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    borderTopWidth: StyleSheet.hairlineWidth,
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
  },
  stat: { fontSize: 11 },
  actionsRow: {
    flexDirection: 'row',
    gap: spacing.md,
    borderTopWidth: StyleSheet.hairlineWidth,
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
  },
  actionText: { fontSize: 13, fontWeight: '600' },
  note: { fontSize: 11, textAlign: 'center', marginTop: spacing.sm },
});
