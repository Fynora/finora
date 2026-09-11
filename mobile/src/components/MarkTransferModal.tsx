import { useState } from 'react';
import {
  ActivityIndicator, FlatList, KeyboardAvoidingView, Modal, Platform, Pressable, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { transactionsApi } from '../api/endpoints';
import type { Transaction } from '../types';
import { EmptyState } from './Card';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency } from '../lib/format';
import { hapticSelection } from '../lib/haptics';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { radius, spacing, useTheme } from '../theme';

/**
 * Phase 6. Port of Ledger.tsx's identical web modal. A keyword search over this same user's
 * transactions (transactionsApi.search, the same endpoint LedgerScreen's own list uses) rather
 * than a dedicated lookup endpoint -- see that file's own comment on why a second "find a
 * transaction" mechanism would be a second place to keep in sync with the first.
 *
 * Client-side, not server-side, exclusions: `transaction.id` itself, and anything already at
 * `reconciliationStatus === 'TRANSFER'` (TransactionService.markTransfer would reject the latter
 * anyway). TransactionDto carries `reconciliationStatus`, not the entity's own `isTransfer`
 * boolean or `transferPairId` -- neither is on the wire.
 */
export function MarkTransferModal({
  transaction, onClose, onMarked,
}: { transaction: Transaction | null; onClose: () => void; onMarked: () => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [keyword, setKeyword] = useState('');
  const debouncedKeyword = useDebouncedValue(keyword, 300);
  const [marking, setMarking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['transfer-candidates', debouncedKeyword],
    queryFn: () => transactionsApi.search({ keyword: debouncedKeyword, size: 10, sortField: 'date', sortDir: 'desc' }),
    enabled: debouncedKeyword.length > 0,
  });

  if (transaction === null) return null;

  const candidates = (data?.content ?? [])
    .filter((cand) => cand.id !== transaction.id && cand.reconciliationStatus !== 'TRANSFER');

  async function pick(candidate: Transaction) {
    if (transaction === null) return;
    hapticSelection();
    setMarking(true);
    setError(null);
    try {
      await transactionsApi.markTransfer(transaction.id, candidate.id);
      onMarked();
    } catch (e) {
      setError(toUserMessage(e, 'Could not mark these as a transfer.'));
      setMarking(false);
    }
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={onClose}
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        />
        <View
          style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}
          accessibilityViewIsModal
        >
          <View style={styles.header}>
            <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">Mark as a transfer</Text>
            <Pressable onPress={onClose} hitSlop={12} accessibilityRole="button">
              <Text style={[styles.done, { color: c.primary }]}>Cancel</Text>
            </Pressable>
          </View>

          <Text style={[styles.subtitle, { color: c.muted }]}>
            {transaction.merchant || transaction.description} · {fmtCurrency(transaction.amount)} ·{' '}
            {transaction.type === 'INCOME' ? 'Income' : 'Expense'}
          </Text>
          <Text style={[styles.subtitle, { color: c.muted, marginBottom: spacing.sm }]}>
            Find the {transaction.type === 'INCOME' ? 'expense' : 'income'} on the other account this money moved to or from.
          </Text>

          <TextInput
            value={keyword}
            onChangeText={setKeyword}
            placeholder="Search description, merchant, or bank…"
            placeholderTextColor={c.muted}
            autoFocus
            autoCapitalize="none"
            autoCorrect={false}
            accessibilityLabel="Search transactions to pair with"
            style={[styles.search, { backgroundColor: c.inputBg, borderColor: c.border, color: c.ink }]}
          />

          {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

          {debouncedKeyword.length === 0 ? (
            <Text style={[styles.hint, { color: c.muted }]}>Start typing to search your transactions.</Text>
          ) : isLoading ? (
            <ActivityIndicator color={c.primary} style={styles.loading} />
          ) : candidates.length === 0 ? (
            <EmptyState message="No matching transactions found." />
          ) : (
            <FlatList
              data={candidates}
              keyExtractor={(item) => item.id}
              style={styles.list}
              renderItem={({ item }) => (
                <Pressable
                  onPress={() => void pick(item)}
                  disabled={marking}
                  testID={`transfer-candidate-${item.id}`}
                  style={[styles.candidate, { borderBottomColor: c.border, opacity: marking ? 0.5 : 1 }]}
                  android_ripple={{ color: c.border }}
                  accessibilityRole="button"
                  accessibilityLabel={`${item.merchant || item.description}, ${item.date}, ${
                    item.type === 'INCOME' ? 'income' : 'expense'
                  } ${fmtCurrency(Math.abs(item.amount))}`}
                >
                  <View style={styles.candidateMain}>
                    <Text style={[styles.candidateMerchant, { color: c.ink }]} numberOfLines={1}>
                      {item.merchant || item.description}
                    </Text>
                    <Text style={[styles.candidateMeta, { color: c.mutedInk }]}>{item.date}</Text>
                  </View>
                  <Text style={[styles.candidateAmount, { color: item.type === 'INCOME' ? c.success : c.danger }]}>
                    {item.type === 'INCOME' ? '+' : '-'}{fmtCurrency(item.amount)}
                  </Text>
                </Pressable>
              )}
            />
          )}
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '80%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
  },
  title: { fontSize: 17, fontWeight: '700' },
  done: { fontSize: 15, fontWeight: '600' },
  subtitle: { fontSize: 12 },
  search: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    marginBottom: spacing.sm,
  },
  error: { fontSize: 13, marginBottom: spacing.sm },
  hint: { fontSize: 13, paddingVertical: spacing.md },
  loading: { marginVertical: spacing.md },
  // Same flexShrink:1 reasoning as OptionPickerModal's own list style -- without it a FlatList
  // taller than the sheet's maxHeight lays out at full content height and nothing scrolls.
  list: { flexGrow: 0, flexShrink: 1 },
  candidate: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  candidateMain: { flex: 1, marginRight: spacing.sm },
  candidateMerchant: { fontSize: 14, fontWeight: '500' },
  candidateMeta: { fontSize: 11, marginTop: 2 },
  candidateAmount: { fontSize: 14, fontWeight: '700' },
});
