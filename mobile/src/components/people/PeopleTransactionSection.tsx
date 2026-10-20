import { useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { peopleApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { fmtCurrency, fromLocalDateString, withSep } from '../../lib/format';
import { hapticError, hapticSuccess } from '../../lib/haptics';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { Transaction } from '../../types';

interface Props {
  transaction: Transaction;
  /** A credit: pick the bill it is a share of. The host closes its own sheet first. */
  onShareOfABill: () => void;
  /** A bill: pick whose money was a share of it. */
  onSplit: () => void;
  /** The person's page, by their key. */
  onOpenPerson: (personKey: string) => void;
}

function dayMonth(date: string): string {
  return withSep(fromLocalDateString(date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

/**
 * The people part of a transaction's details (spec §7, "Bill"): a bill shows what was paid, the
 * user's share and each share with Undo (screen 4 of the share mockup); any expense can be split; a
 * payment to a person can be marked lent; money from a person can be a share of a bill, paid back or
 * borrowed. A transfer, refund or card payment is none of these, and shows nothing.
 */
export function PeopleTransactionSection({ transaction: t, onShareOfABill, onSplit, onOpenPerson }: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState<string | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  const plain = t.reconciliationStatus === 'OK';
  const isBill = t.type === 'EXPENSE' && plain;
  const fromPerson = t.type === 'INCOME' && plain && t.counterpartyType === 'PERSON';
  // An older server has no such endpoint: the shares are then simply not shown.
  const sharesQ = useQuery({
    queryKey: ['people', 'bill', t.id],
    queryFn: () => peopleApi.billShares(t.id),
    enabled: isBill,
    retry: false,
  });
  // A payee key that does not name one person has no page: the call fails and the link stays away.
  const personQ = useQuery({
    queryKey: ['people', 'of', t.id],
    queryFn: () => peopleApi.personOf(t.id),
    enabled: plain && t.counterpartyType === 'PERSON',
    retry: false,
  });

  if (!isBill && !fromPerson) return null;

  async function save(write: () => Promise<unknown>, done: string | null) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setFailed(null);
    setSaved(null);
    try {
      await write();
      hapticSuccess();
      invalidateFinancialData(queryClient);
      setSaved(done);
    } catch (err) {
      hapticError();
      setFailed(toUserMessage(err, "Couldn't save that. Try again."));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const shares = sharesQ.data ?? [];
  const personKey = personQ.data?.personKey;
  const cameBack = shares.reduce((sum, s) => sum + s.amount, 0);
  const amount = Math.abs(t.amount);

  // `then`: another sheet or page. `write`: an answer saved here, with what to say once saved.
  type Go = { then: () => void } | { write: () => Promise<unknown>; done: string };
  const actions: { label: string; icon: keyof typeof Ionicons.glyphMap; go: Go }[] = [
    ...(isBill ? [{ label: 'Split this', icon: 'people-outline' as const, go: { then: onSplit } }] : []),
    ...(isBill && t.counterpartyType === 'PERSON'
      ? [{ label: 'Lent', icon: 'arrow-up-outline' as const, go: { write: () => peopleApi.addEntry(t.id, 'LENT'), done: 'Saved as lent.' } }]
      : []),
    ...(fromPerson ? [
      { label: 'Share of a bill', icon: 'receipt-outline' as const, go: { then: onShareOfABill } },
      { label: 'Paid back', icon: 'arrow-down-outline' as const, go: { write: () => peopleApi.addEntry(t.id, 'PAID_BACK'), done: 'Saved as paid back.' } },
      { label: 'Borrowed', icon: 'swap-vertical-outline' as const, go: { write: () => peopleApi.addEntry(t.id, 'BORROWED'), done: 'Saved as borrowed.' } },
    ] : []),
    ...(personQ.data ? [{ label: `See ${personQ.data.label}`, icon: 'person-outline' as const, go: { then: () => onOpenPerson(personKey as string) } }] : []),
  ];

  return (
    <View style={styles.section}>
      <Text style={[typography.labelS, styles.heading, { color: c.mutedInk }]}>With people</Text>
      {isBill && shares.length > 0 ? (
        <View style={[styles.shares, { borderColor: c.border }]}>
          <Text style={[typography.numberS, { color: c.ink }]}>{`Your share ${fmtCurrency(Math.max(0, amount - cameBack))}`}</Text>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`Paid ${fmtCurrency(amount)} · ${fmtCurrency(cameBack)} came back`}</Text>
          {shares.map((s) => (
            <View key={s.creditId} style={styles.shareRow}>
              <Text style={[typography.bodyM, styles.shareText, { color: c.ink }]}>{`${s.label} · ${dayMonth(s.date)}`}</Text>
              <Text style={[typography.numberS, { color: c.successInk }]}>{`+${fmtCurrency(s.amount)}`}</Text>
              {s.fromRule ? (
                // A rule's share is undone by removing the rule, on the person's page.
                <Text style={[typography.labelS, styles.undo, { color: c.mutedInk }]}>Every month</Text>
              ) : (
                <Pressable
                  onPress={() => void save(() => peopleApi.unlink(s.creditId, t.id), null)}
                  disabled={busy}
                  accessibilityRole="button"
                  accessibilityLabel={`Undo the share from ${s.label}`}
                  hitSlop={8}
                  style={styles.undo}
                >
                  <Text style={[typography.labelS, { color: c.primary }]}>Undo</Text>
                </Pressable>
              )}
            </View>
          ))}
        </View>
      ) : null}
      <View>
        {actions.map(({ label, icon, go }) => (
          <Pressable
            key={label}
            onPress={() => ('then' in go ? go.then() : void save(go.write, go.done))}
            disabled={busy}
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{ disabled: busy }}
            style={[styles.action, { borderBottomColor: c.border }, busy && styles.disabled]}
          >
            <Ionicons name={icon} size={20} color={c.ink} style={styles.actionIcon} />
            <Text style={[styles.actionLabel, { color: c.ink }]}>{label}</Text>
            <Ionicons name="chevron-forward" size={18} color={c.muted} />
          </Pressable>
        ))}
      </View>
      {saved ? <Text style={[typography.bodyS, styles.status, { color: c.successInk }]}>{saved}</Text> : null}
      {failed ? (
        <Text accessibilityRole="alert" style={[typography.bodyS, styles.status, { color: c.dangerInk }]}>
          {failed}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginBottom: spacing.md },
  heading: { marginBottom: spacing.xs },
  shares: { borderWidth: 1, borderRadius: radius.md, padding: spacing.sm, marginBottom: spacing.sm, gap: 2 },
  shareRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 44 },
  shareText: { flex: 1 },
  // 44pt minimum touch target (Apple HIG).
  undo: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  action: { flexDirection: 'row', alignItems: 'center', paddingVertical: 14, borderBottomWidth: StyleSheet.hairlineWidth },
  actionIcon: { marginRight: spacing.sm },
  actionLabel: { fontSize: 15, flex: 1 },
  disabled: { opacity: 0.5 },
  status: { marginTop: spacing.xs },
});
