import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { billingApi, type BillingHistoryEntry } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtDate } from '../lib/format';
import { spacing, useTheme } from '../theme';
import { Card, EmptyState, SectionHeading } from './Card';

// Same rounding and non-INR fallback as frontend/src/pages/Billing.tsx's own fmt.
function fmtAmount(amount: number, currency: string) {
  const symbol = currency === 'INR' ? '₹' : currency + ' ';
  return symbol + Math.round(amount).toLocaleString('en-IN');
}

function referenceOf(entry: BillingHistoryEntry) {
  return entry.id.slice(0, 8).toUpperCase();
}

/** Mobile counterpart of the Billing history table in frontend/src/pages/Billing.tsx. One "Invoice"
 *  action rather than the web's View + Download pair: the share sheet already offers both. */
export function BillingHistorySection({ paymentProvider }: { paymentProvider: string | null }) {
  const c = useTheme();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { data: payments, isLoading, isError } = useQuery({
    queryKey: ['billing-history'],
    queryFn: () => billingApi.history(),
  });

  function statusLabel(status: string) {
    switch (status) {
      case 'SUCCESS': return { text: 'Paid', color: c.successInk };
      case 'REFUNDED': return { text: 'Refunded', color: c.muted };
      case 'FAILED': return { text: 'Failed', color: c.danger };
      default: return { text: 'Pending', color: c.warningInk };
    }
  }

  async function openInvoice(entry: BillingHistoryEntry) {
    if (busyId) return;
    setBusyId(entry.id);
    setError(null);
    try {
      await billingApi.downloadInvoice(entry.id, `Fynora-invoice-${referenceOf(entry)}.pdf`);
    } catch (e) {
      setError(toUserMessage(e, 'Could not open this invoice. Try again.'));
    } finally {
      setBusyId(null);
    }
  }

  // Nothing to say while loading: an "empty" message here would flash before every real list.
  if (isLoading) return null;

  return (
    <Card>
      <SectionHeading title="Billing history" />
      {isError ? (
        <Text style={[styles.note, { color: c.muted }]}>Couldn't load your billing history. Try again later.</Text>
      ) : !payments || payments.length === 0 ? (
        <EmptyState
          message={
            paymentProvider === 'REVENUECAT'
              ? 'Payments made through the App Store or Google Play are listed in your store account, not here.'
              : "No billing history yet. Payment records will appear here once you've made your first payment."
          }
        />
      ) : (
        payments.map((p) => {
          const status = statusLabel(p.status);
          return (
            <View key={p.id} style={[styles.row, { borderBottomColor: c.border }]}>
              <View style={styles.rowMain}>
                <Text style={[styles.amount, { color: c.ink }]}>{fmtAmount(p.amount, p.currency)}</Text>
                <Text style={[styles.meta, { color: c.muted }]}>
                  {fmtDate(p.createdAt)} · {referenceOf(p)}
                </Text>
              </View>
              <View style={styles.rowEnd}>
                <Text style={[styles.status, { color: status.color }]}>{status.text}</Text>
                {/* Only a completed charge has anything to invoice -- InvoiceService answers 409
                    for PENDING/FAILED/REFUNDED, same rule as the web table. */}
                {p.status === 'SUCCESS' ? (
                  <Pressable
                    onPress={() => void openInvoice(p)}
                    disabled={busyId !== null}
                    hitSlop={8}
                    accessibilityRole="button"
                    accessibilityLabel={`Invoice for payment ${referenceOf(p)}`}
                    accessibilityState={{ disabled: busyId !== null }}
                  >
                    <Text style={[styles.action, { color: c.primary, opacity: busyId !== null ? 0.5 : 1 }]}>
                      {busyId === p.id ? 'Opening…' : 'Invoice'}
                    </Text>
                  </Pressable>
                ) : null}
              </View>
            </View>
          );
        })
      )}
      {error ? <Text style={[styles.note, { color: c.danger }]}>{error}</Text> : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  rowMain: { flexShrink: 1 },
  rowEnd: { alignItems: 'flex-end', gap: 2 },
  amount: { fontSize: 15, fontWeight: '600' },
  meta: { fontSize: 12, marginTop: 2 },
  status: { fontSize: 12, fontWeight: '600' },
  action: { fontSize: 13, fontWeight: '600' },
  note: { fontSize: 13, marginTop: spacing.sm },
});
