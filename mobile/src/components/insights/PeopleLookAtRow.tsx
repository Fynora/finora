import { useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueryClient } from '@tanstack/react-query';
import { inflowApi, peopleApi, transactionsApi, type PeopleSuggestion } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { fmtCurrency, fromLocalDateString, withSep } from '../../lib/format';
import { hapticError, hapticSuccess } from '../../lib/haptics';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import { radius, spacing, typography, useTheme } from '../../theme';

/** The system category a lent payment the user calls a gift goes under (spec §5, "Let it go"). For
 *  this payment only: one gift says nothing about the person's other payments, so nothing is learned. */
const GIFT_CATEGORY = 'Gifts & Donations';

interface Props {
  item: PeopleSuggestion;
  /** Today, YYYY-MM-DD: how long a balance has been open. */
  today: string;
  /** The built-in Paid back kind, for "Always do this"; null until the kinds have loaded. */
  paidBackKindId: string | null;
  onAck: () => void;
  onPickBill: (item: PeopleSuggestion) => void;
  onAsk: (item: PeopleSuggestion) => void;
  /** "Paid for something": the payment, to give it a category. */
  onOpenPayment: (item: PeopleSuggestion) => void;
  /** After an answer is saved (the bill picker closes on it). */
  onAnswered?: () => void;
}

interface Chip {
  text: string;
  /** Spoken name, when the text alone would be ambiguous. */
  name?: string;
  primary?: boolean;
  /** An answer saved to the server: one at a time, then every figure refreshes. */
  write?: () => Promise<unknown>;
  /** Anything else: the screen's own handling. */
  onPress?: () => void;
}

/** "4 Oct": a day and a short month, Sep never Sept. */
function dayMonth(date: string): string {
  return withSep(fromLocalDateString(date).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }));
}

function daysBetween(from: string, to: string): number {
  return Math.round((fromLocalDateString(to).getTime() - fromLocalDateString(from).getTime()) / 86_400_000);
}

/**
 * One "To look at" row about money with people (spec §6 A-G; copy from the approved mockups of
 * 2026-10-18). Every answer goes to the server and refreshes every figure, since a share or a loan
 * moves spending and income; "Got it" and the "not now" answers go through the screen's own
 * acknowledgement, which hides the row at once. Nothing here changes a figure until tapped.
 */
export function PeopleLookAtRow({
  item, today, paidBackKindId, onAck, onPickBill, onAsk, onOpenPayment, onAnswered,
}: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  // The guard itself: state only disables the chips on the next render, too late for two taps in one frame.
  const saving = useRef(false);
  const [failed, setFailed] = useState<string | null>(null);
  // "Not a share": what the credit was, asked in place (spec §6 D).
  const [notAShare, setNotAShare] = useState(false);

  const amount = fmtCurrency(item.amount);
  const label = item.label;
  const txnId = item.txnId ?? '';

  async function answer(write: () => Promise<unknown>) {
    if (saving.current) return;
    saving.current = true;
    setBusy(true);
    setFailed(null);
    try {
      await write();
      hapticSuccess();
      invalidateFinancialData(queryClient);
      onAnswered?.();
    } catch (err) {
      hapticError();
      // The server's own sentence when it refuses (a sender it cannot tell apart, nothing left).
      setFailed(toUserMessage(err, "Couldn't save that. Try again."));
    } finally {
      saving.current = false;
      setBusy(false);
    }
  }

  const gotIt = (text = 'Got it'): Chip => ({ text, name: `${text}: ${label}`, onPress: onAck });
  const owedOrBorrowed = (part?: number): Chip[] => [
    { text: 'They owed you', write: (() => peopleApi.addEntry(txnId, 'PAID_BACK', part)) },
    { text: 'Borrowed', write: (() => peopleApi.addEntry(txnId, 'BORROWED', part)) },
  ];

  let icon: keyof typeof Ionicons.glyphMap = 'arrow-down';
  let line: string;
  let question: string | null = null;
  let chips: Chip[];

  switch (item.kind) {
    case 'SHARE_CHECK':
      line = `${amount} from ${label}${item.date ? ` on ${dayMonth(item.date)}` : ''}.`
        + (item.category ? ` You filed it under ${item.category}.` : '');
      if (notAShare) {
        question = 'What was it, then?';
        chips = [...owedOrBorrowed(), gotIt('Something else')];
      } else {
        question = 'Is it their share of a bill you paid?';
        chips = [
          { text: 'Yes, pick the bill', primary: true, onPress: () => onPickBill(item) },
          { text: 'Not a share', onPress: () => setNotAShare(true) },
          gotIt(),
        ];
      }
      break;
    case 'LEFTOVER_CHECK':
      line = `${amount} of ${label}'s payment${item.date ? ` on ${dayMonth(item.date)}` : ''} is left after the bills it paid toward.`;
      question = 'What was the rest?';
      chips = [...owedOrBorrowed(item.amount), gotIt()];
      break;
    case 'LENT_CHECK':
      icon = 'arrow-up';
      line = `${amount} to ${label}${item.date ? ` on ${dayMonth(item.date)}` : ''}. Nothing back yet.`;
      question = 'What was it?';
      chips = [
        { text: `Lent to ${label}`, primary: true, write: (() => peopleApi.addEntry(txnId, 'LENT')) },
        { text: 'Paid for something', onPress: () => { onOpenPayment(item); onAck(); } },
        { text: 'Gift', write: (async () => { await transactionsApi.updateCategory(txnId, GIFT_CATEGORY, 'ONLY_THIS'); onAck(); }) },
        gotIt(),
      ];
      break;
    case 'PAYBACK_CHECK':
      line = `${amount} from ${label}${item.date ? ` on ${dayMonth(item.date)}` : ''}.`;
      question = item.owed != null
        ? `Paying back what you lent? ${label} owes you ${fmtCurrency(item.owed)}.`
        : 'Paying back what you lent?';
      chips = [
        {
          text: 'Yes, paid back',
          primary: true,
          // The second form (spec §6 C): the earlier payment becomes the loan this pays back.
          write: (async () => {
            if (item.relatedTxnId) await peopleApi.addEntry(item.relatedTxnId, 'LENT');
            await peopleApi.addEntry(txnId, 'PAID_BACK');
          }),
        },
        gotIt('Something else'),
      ];
      break;
    case 'SETTLE': {
      icon = 'hourglass-outline';
      const days = item.date ? daysBetween(item.date, today) : null;
      line = `${label} owes you ${fmtCurrency(item.owed ?? item.amount)}${days != null ? `, ${days} days` : ''}.`;
      chips = [
        { text: `Ask ${label}`, primary: true, onPress: () => onAsk(item) },
        { text: 'Paid in cash', write: (() => peopleApi.settle(item.personKey, 'CASH')) },
        { text: 'Let it go', write: (() => peopleApi.settle(item.personKey, 'LET_GO')) },
        gotIt('Keep waiting'),
      ];
      break;
    }
    case 'EVERY_MONTH':
      icon = 'repeat';
      line = `${label} sent a share in other months too. Link their ${item.category ?? 'bill'} shares every month?`;
      chips = [
        {
          text: 'Every month',
          primary: true,
          write: (() => peopleApi.addSplitRule(txnId, item.relatedTxnId ?? '')),
        },
        gotIt('Just this one'),
      ];
      break;
    case 'ALWAYS_PERSON':
      icon = 'checkmark-done';
      line = `${amount} from ${label} was money paid back.`;
      question = `Count money from ${label} as paid back every time?`;
      chips = [
        ...(paidBackKindId
          ? [{
              text: `Always do this for ${label}`,
              primary: true,
              write: (() => inflowApi.setChoice(txnId, paidBackKindId, 'SENDER')),
            }]
          : []),
        gotIt('Ask each time'),
      ];
      break;
  }

  return (
    <View style={styles.row}>
      <View style={styles.rowHead}>
        <View style={[styles.icon, { backgroundColor: c.primaryLight }]}>
          <Ionicons name={icon} size={16} color={c.primary} />
        </View>
        <View style={styles.text}>
          <Text style={[typography.bodyM, { color: c.ink }]}>{line}</Text>
          {question ? <Text style={[typography.labelM, { color: c.ink }]}>{question}</Text> : null}
        </View>
      </View>
      <View style={styles.actions}>
        {chips.map((chip) => (
          <Pressable
            key={chip.text}
            onPress={() => (chip.write ? answer(chip.write) : chip.onPress?.())}
            disabled={busy}
            accessibilityRole="button"
            accessibilityLabel={chip.name ?? chip.text}
            accessibilityState={{ disabled: busy }}
            style={[
              styles.chip,
              chip.primary
                ? { backgroundColor: c.primary, borderColor: c.primary }
                : { borderColor: c.border },
              busy && styles.busy,
            ]}
          >
            <Text style={[typography.labelS, { color: chip.primary ? c.onPrimary : c.ink }]}>{chip.text}</Text>
          </Pressable>
        ))}
      </View>
      {failed ? (
        <Text accessibilityRole="alert" style={[typography.bodyS, styles.error, { color: c.dangerInk }]}>
          {failed}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { paddingVertical: spacing.sm, gap: spacing.xs },
  rowHead: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, minHeight: 44 },
  icon: { width: 28, height: 28, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1, gap: 2 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, paddingLeft: 28 + spacing.sm },
  // 44pt minimum touch target (Apple HIG), as the other To look at chips.
  chip: {
    minHeight: 44, paddingHorizontal: spacing.md, borderWidth: 1, borderRadius: radius.xl,
    alignItems: 'center', justifyContent: 'center',
  },
  busy: { opacity: 0.5 },
  error: { paddingLeft: 28 + spacing.sm },
});
