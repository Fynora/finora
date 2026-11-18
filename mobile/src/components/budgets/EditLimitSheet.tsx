import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Button } from '../Button';
import { TextField } from '../TextField';
import { spacing, typography, useTheme } from '../../theme';
import { budgetPace, dayCount, limitProblem, parseLimit, type MonthClock } from '../../lib/budgetPace';
import { fmtCurrency } from '../../lib/format';
import { hapticWarning } from '../../lib/haptics';
import { toUserMessage } from '../../lib/apiError';
import { useSingleFlight } from '../../lib/useSingleFlight';
import type { Budget } from '../../types';
import { BudgetSheet } from './BudgetSheet';

const CHIPS = [500, 1000, 2000];

/** What the typed limit would mean for the rest of the month, worked out live as it changes. */
function limitHint(budget: Budget, typed: number | null, clock: MonthClock): string | null {
  if (typed === null) return null;
  const spent = fmtCurrency(budget.spentThisMonth);
  if (budget.spentThisMonth === typed) return `${spent} spent so far, all of this limit.`;
  if (budget.spentThisMonth > typed) return `${spent} spent so far, already more than ${fmtCurrency(typed)}.`;
  if (clock.daysLeft === 0) return `${spent} spent so far. Today is the last day of the month.`;
  const perDay = budgetPace(budget.spentThisMonth, typed, clock).safePerDay;
  return `${spent} spent so far. At ${fmtCurrency(typed)} you can spend ${fmtCurrency(perDay)} a day for the next ${dayCount(clock.daysLeft)}.`;
}

/**
 * Edit one budget's limit. Mounted fresh for each budget (BudgetsScreen keys it), so the field
 * always starts at that budget's current limit. `onSave` rejects with the server error, which this
 * sheet shows while staying open.
 */
export function EditLimitSheet({ budget, clock, onClose, onSave }: {
  budget: Budget; clock: MonthClock; onClose: () => void; onSave: (limit: number) => Promise<void>;
}) {
  const c = useTheme();
  const [text, setText] = useState(String(budget.monthlyLimit));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const singleFlight = useSingleFlight();
  const typed = parseLimit(text);
  const hint = limitHint(budget, typed, clock);

  async function save() {
    // Inside the single-flight guard, so a second tap while a save is in flight is ignored.
    await singleFlight(async () => {
      const amount = parseLimit(text);
      if (amount === null) {
        setError(limitProblem(text) ?? 'Monthly limit must be a number greater than zero.');
        hapticWarning();
        return;
      }
      setError(null);
      setSaving(true);
      try {
        await onSave(amount);
      } catch (e) {
        setError(toUserMessage(e, 'Could not save this budget. Try again.'));
        setSaving(false);
      }
    });
  }

  return (
    <BudgetSheet visible title={budget.categoryName} onClose={onClose} closeDisabled={saving}>
      <TextField
        label="Monthly limit"
        value={text}
        onChangeText={(v) => { setText(v); setError(null); }}
        keyboardType="decimal-pad"
        prefix="₹"
        error={error ?? undefined}
      />
      <View style={styles.chips}>
        {CHIPS.map((n) => (
          <Pressable
            key={n}
            onPress={() => { setText(String((parseLimit(text) ?? 0) + n)); setError(null); }}
            style={[styles.chip, { backgroundColor: c.bg /* glass-exempt: amount chip inside the glass sheet; opaque bg is its contrast */, borderColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={`Add ${fmtCurrency(n)}`}
          >
            <Text style={[typography.labelS, { color: c.ink }]}>{`+${n.toLocaleString('en-IN')}`}</Text>
          </Pressable>
        ))}
      </View>
      {/* Errors print in TextField's own fixed slot under the field. */}
      {hint ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{hint}</Text> : null}
      <Button label={saving ? 'Saving…' : 'Save limit'} onPress={() => void save()} loading={saving} />
    </BudgetSheet>
  );
}

const styles = StyleSheet.create({
  chips: { flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' },
  chip: { borderWidth: 1, borderRadius: 999, paddingHorizontal: 14, minHeight: 36, justifyContent: 'center' },
});
