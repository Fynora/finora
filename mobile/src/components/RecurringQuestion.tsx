import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { categoriesApi, recurringApi, type RecurringQuestionState } from '../api/endpoints';
import { fmtCurrency } from '../lib/format';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';
import { radius, spacing, useTheme } from '../theme';
import { CategoryPickerModal } from './CategoryPickerModal';

/** What a repeating payment usually is, in the order the chips show. Mirrors web's SHORT_LIST. */
export const SHORT_LIST = ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments'] as const;

interface Props {
  merchant: string;
  state: RecurringQuestionState;
  answer: string | null;
  /** The average for NEEDS_ANSWER, the latest payment otherwise. */
  amount: number;
  /** RecurringItem.label ("Monthly", "Weekly", ...). */
  label?: string;
}

/**
 * The recurring-payment question on a recurring row (docs/superpowers/specs/2026-10-02-recurring-
 * payment-answer-design.md §4), the mobile twin of web's RecurringQuestion: ask once what a
 * repeating "Other" payment is, show the answer with a way to change it, and re-ask when the amount
 * moves out of the saved range. A chip appears only for a category the user still has. A save
 * refreshes through invalidateFinancialData, which covers 'recurring' and the transaction keys and
 * goes through the gated change-sync client.
 */
export function RecurringQuestion({ merchant, state, answer, amount, label = 'Monthly' }: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [changing, setChanging] = useState(false);
  const [picking, setPicking] = useState(false);
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list() });
  const save = useMutation({
    mutationFn: (category: string) => recurringApi.categorize(merchant, category),
    onSuccess: () => {
      setChanging(false);
      setPicking(false);
      invalidateFinancialData(queryClient);
    },
  });

  if (state === 'NONE') return null;

  const owned = new Set((categoriesQ.data ?? []).map((cat) => cat.name.toLowerCase()));
  const chips = SHORT_LIST.filter((name) => owned.has(name.toLowerCase()));
  const asking = state === 'NEEDS_ANSWER' || changing;
  const chipStyle = [styles.chip, { borderColor: c.border }];
  const chipText = [styles.chipText, { color: c.ink }];

  if (!asking && state === 'ANSWERED') {
    return (
      <View style={styles.row}>
        <Text style={[styles.text, { color: c.ink }]}>{answer}</Text>
        <Pressable accessibilityRole="button" onPress={() => setChanging(true)} style={styles.link}>
          <Text style={[styles.linkText, { color: c.primary }]}>Change</Text>
        </Pressable>
      </View>
    );
  }

  if (!asking && state === 'AMOUNT_CHANGED') {
    return (
      <View style={styles.wrap}>
        <Text style={[styles.text, { color: c.ink }]}>{`${fmtCurrency(amount)} to ${merchant} — still ${answer}?`}</Text>
        <View style={styles.row}>
          <Pressable
            accessibilityRole="button"
            disabled={save.isPending || !answer}
            onPress={() => answer && save.mutate(answer)}
            style={chipStyle}
          >
            <Text style={chipText}>Yes</Text>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={() => setChanging(true)} style={styles.link}>
            <Text style={[styles.linkText, { color: c.primary }]}>Change</Text>
          </Pressable>
        </View>
        {save.isError ? <Text style={[styles.text, { color: c.dangerInk }]}>Couldn't save — try again.</Text> : null}
      </View>
    );
  }

  return (
    <View style={styles.wrap}>
      <Text style={[styles.text, { color: c.ink }]}>{`What is this ${fmtCurrency(amount)} ${label.toLowerCase()} payment?`}</Text>
      <View style={styles.chips}>
        {chips.map((name) => (
          <Pressable
            key={name}
            accessibilityRole="button"
            disabled={save.isPending}
            onPress={() => save.mutate(name)}
            style={chipStyle}
          >
            <Text style={chipText}>{name}</Text>
          </Pressable>
        ))}
        <Pressable
          accessibilityRole="button"
          disabled={save.isPending}
          onPress={() => setPicking(true)}
          style={[...chipStyle, styles.dashed]}
        >
          <Text style={chipText}>Something else</Text>
        </Pressable>
        {changing ? (
          <Pressable
            accessibilityRole="button"
            disabled={save.isPending}
            onPress={() => setChanging(false)}
            style={styles.link}
          >
            <Text style={[styles.linkText, { color: c.primary }]}>Cancel</Text>
          </Pressable>
        ) : null}
      </View>
      {save.isError ? <Text style={[styles.text, { color: c.dangerInk }]}>Couldn't save — try again.</Text> : null}
      <CategoryPickerModal
        visible={picking}
        selectedName={null}
        allowManage={false}
        title="What is this payment?"
        // Closed on pick, so the saving state and any error show on the card rather than behind the sheet.
        onSelect={(category) => {
          setPicking(false);
          save.mutate(category.name);
        }}
        onClose={() => setPicking(false)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: spacing.xs, gap: spacing.xs },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: spacing.xs },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  // 44pt minimum touch target (Apple HIG), the same floor the recurring card's dismiss button uses.
  chip: {
    minHeight: 44, paddingHorizontal: spacing.md, borderWidth: 1, borderRadius: radius.xl,
    alignItems: 'center', justifyContent: 'center',
  },
  dashed: { borderStyle: 'dashed' },
  chipText: { fontSize: 13 },
  text: { fontSize: 13 },
  link: { minHeight: 44, justifyContent: 'center', paddingHorizontal: spacing.xs },
  linkText: { fontSize: 13, fontWeight: '600' },
});
