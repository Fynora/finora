import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { billingApi, type BillingHistoryEntry } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { fmtDate } from '../lib/format';
import { isPausedCold } from '../lib/refreshingIndicator';
import { spacing, useTheme } from '../theme';
import { Card, EmptyState, SectionHeading } from './Card';

// Same rounding and non-INR fallback as frontend/src/pages/Billing.tsx's own fmt, except a zero
// amount reads "—": the backend records a retry attempt with amount 0 because the webhook carries
// no figure (RazorpayWebhookDispatcher's subscription.pending handler), and "₹0" would state a
// charge amount nobody knows.
function fmtAmount(amount: number, currency: string) {
  if (amount === 0) return '—';
  const symbol = currency === 'INR' ? '₹' : currency + ' ';
  return symbol + Math.round(amount).toLocaleString('en-IN');
}

function referenceOf(entry: BillingHistoryEntry) {
  return entry.id.slice(0, 8).toUpperCase();
}

/** Mobile counterpart of the Billing history table in frontend/src/pages/Billing.tsx. One "Invoice"
 *  action rather than the web's View + Download pair: the share sheet already offers both. */
export function BillingHistorySection({ paymentProvider, hideWhenEmpty = false }: {
  paymentProvider: string | null;
  // The Paywall shows this too, so a lapsed payer can still fetch an old invoice. Someone who has
  // never paid must see nothing there -- not an empty-history card, and not a load-failure note
  // about a history they never had.
  hideWhenEmpty?: boolean;
}) {
  const c = useTheme();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const historyQ = useQuery({
    queryKey: ['billing-history'],
    queryFn: () => billingApi.history(),
  });
  const { data: payments, isLoading } = historyQ;
  // A cold query paused for lack of connectivity is neither an error nor "no payments" -- see
  // isPausedCold. Saying there is no history there would tell someone with real invoices they have
  // none.
  //
  // isError alone is not "nothing to show": React Query keeps the previous data when a background
  // refetch fails, so a failed refetch must not hide invoices that loaded fine a moment ago.
  const hasData = payments !== undefined;
  const loadFailed = (historyQ.isError && !hasData) || isPausedCold(historyQ);
  const refreshFailed = historyQ.isError && hasData;

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
    const startedAt = requestStartedAt();
    try {
      await billingApi.downloadInvoice(entry.id, `Fynora-invoice-${referenceOf(entry)}.pdf`);
    } catch (e) {
      reportTransportFailure(e, 'billing-history:open-invoice', startedAt);
      setError(toUserMessage(e, 'Could not open this invoice. Try again.'));
    } finally {
      setBusyId(null);
    }
  }

  // Nothing to say while loading: an "empty" message here would flash before every real list.
  if (isLoading) return null;
  if (hideWhenEmpty && (loadFailed || !payments || payments.length === 0)) return null;

  return (
    <Card>
      <SectionHeading title="Billing history" />
      {refreshFailed ? (
        <Text style={[styles.note, { color: c.muted }]}>Couldn't refresh. Showing what was last loaded.</Text>
      ) : null}
      {loadFailed ? (
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
                    hitSlop={{ top: 14, bottom: 14, left: 12, right: 12 }}
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
