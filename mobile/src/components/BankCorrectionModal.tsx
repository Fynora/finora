import { useState } from 'react';
import { ActivityIndicator, Modal, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { transactionsApi } from '../api/endpoints';
import type { Transaction } from '../types';
import { Button } from './Button';
import { Card, EmptyState, SectionHeading } from './Card';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency } from '../lib/format';
import { spacing, useTheme } from '../theme';

/**
 * Plan 6, Track B mobile parity -- old-vs-new detail behind the "Bank Correction" badge
 * (LedgerScreen.tsx's statusBadges), sourced from the transaction's own AuditLog trail
 * (transactionsApi.correctionHistory) rather than any new Transaction columns. Same plain
 * Modal + Card + backdrop shape and same lazy `enabled`-gated useQuery keyed by id as
 * TransactionExplanationModal, the closest existing precedent for an on-demand detail panel in
 * this codebase; the acknowledge action mirrors MarkTransferModal's try/catch/setError shape.
 *
 * Never mutates the transaction's own amount/description/etc. -- round 3's "preserve, don't
 * overwrite" decision (see AccountAggregatorTransactionDiffService's own class doc on the
 * backend) holds all the way through to this view too. Acknowledging only clears the flag.
 */
export function BankCorrectionModal({
  transaction, onClose, onAcknowledged,
}: { transaction: Transaction | null; onClose: () => void; onAcknowledged: () => void }) {
  const c = useTheme();
  const [acknowledging, setAcknowledging] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data: history, isLoading, isError } = useQuery({
    queryKey: ['correction-history', transaction?.id],
    queryFn: () => transactionsApi.correctionHistory(transaction!.id),
    enabled: transaction !== null,
  });

  if (transaction === null) return null;

  function actionLabel(action: string): string {
    switch (action) {
      case 'ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED': return 'Bank reported a different value';
      case 'ACCOUNT_AGGREGATOR_TRANSACTION_MISSING': return 'No longer reported by the bank';
      case 'ACCOUNT_AGGREGATOR_CORRECTION_ACKNOWLEDGED': return 'You acknowledged this';
      default: return action;
    }
  }

  async function acknowledge() {
    setAcknowledging(true);
    setError(null);
    try {
      await transactionsApi.acknowledgeBankCorrection(transaction!.id);
      onAcknowledged();
    } catch (e) {
      setError(toUserMessage(e, 'Could not acknowledge this correction.'));
      setAcknowledging(false);
    }
  }

  return (
    <Modal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <Card style={styles.card}>
          <SectionHeading title="Bank correction" />
          <Text style={[styles.contextLine, { color: c.muted }]}>
            {transaction.description || transaction.merchant} · currently {fmtCurrency(Math.abs(transaction.amount))} in Fynora
          </Text>
          {isLoading ? (
            <ActivityIndicator color={c.primary} style={styles.loading} />
          ) : isError ? (
            <EmptyState message="Couldn't load this correction's history." />
          ) : (
            <View style={styles.body}>
              {(history ?? []).map((entry, i) => (
                <View key={i} style={styles.entry}>
                  <Text style={[styles.entryLabel, { color: c.ink }]}>{actionLabel(entry.action)}</Text>
                  {typeof entry.metadata.previousAmount !== 'undefined' ? (
                    <Text style={[styles.entryDetail, { color: c.muted }]}>
                      {fmtCurrency(Number(entry.metadata.previousAmount))} → {fmtCurrency(Number(entry.metadata.newAmount))}
                    </Text>
                  ) : null}
                  {typeof entry.metadata.amount !== 'undefined' ? (
                    <Text style={[styles.entryDetail, { color: c.muted }]}>
                      {fmtCurrency(Number(entry.metadata.amount))} on {String(entry.metadata.txnDate ?? '')}
                    </Text>
                  ) : null}
                  <Text style={[styles.entryDate, { color: c.mutedInk }]}>
                    {new Date(entry.createdAt).toLocaleString()}
                  </Text>
                </View>
              ))}
            </View>
          )}
          {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
          <Button label="Acknowledge" onPress={acknowledge} loading={acknowledging} />
          <Button label="Close" variant="link" onPress={onClose} />
        </Card>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1, backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center', justifyContent: 'center', padding: spacing.md,
  },
  card: { width: '100%', maxWidth: 420, gap: spacing.sm },
  contextLine: { fontSize: 12, marginTop: -spacing.xs },
  loading: { marginVertical: spacing.md },
  body: { gap: spacing.md },
  entry: { gap: 2 },
  entryLabel: { fontSize: 14, fontWeight: '600' },
  entryDetail: { fontSize: 13 },
  entryDate: { fontSize: 11 },
  error: { fontSize: 12 },
});
