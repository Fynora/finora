import { Pressable, StyleSheet, Text, View } from 'react-native';
import { TextField } from '../../components/TextField';
import { readAmountOwed } from '../../lib/amountOwed';
import { radius, spacing, useTheme } from '../../theme';

/**
 * "How much do you owe on this card today?" on the import review screen, shown when a card
 * statement gives no closing balance and nothing has stated the card's figure (lib/amountOwed.ts).
 * Optional: skipping imports the statement and the card shows "Amount owed not known" until the
 * user adds it. Same wording and rules as the web review screen.
 */
export function AmountOwedQuestion({
  value,
  onChange,
  skipped,
  onSkip,
  onUndoSkip,
}: {
  value: string;
  onChange: (value: string) => void;
  skipped: boolean;
  onSkip: () => void;
  onUndoSkip: () => void;
}) {
  const c = useTheme();
  const invalid = readAmountOwed(value).state === 'invalid';

  if (skipped) {
    return (
      <View style={[styles.box, { borderColor: c.border }]} testID="import-amount-owed-skipped">
        <Text style={[styles.help, { color: c.muted }]}>
          Skipped. Until you add it, the card will show "Amount owed not known" and stays out of your totals.
        </Text>
        <Pressable onPress={onUndoSkip} accessibilityRole="button" accessibilityLabel="Answer now" hitSlop={8}>
          <Text style={[styles.link, { color: c.primary }]}>Answer now</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <View style={[styles.box, { borderColor: c.border }]} testID="import-amount-owed">
      <Text style={[styles.question, { color: c.ink }]}>How much do you owe on this card today?</Text>
      <Text style={[styles.help, { color: c.muted }]}>
        This statement doesn't show your balance. Your card app or latest bill has it.
      </Text>
      <TextField
        label="Amount owed today (₹)"
        value={value}
        onChangeText={onChange}
        keyboardType="decimal-pad"
        placeholder="0.00"
        error={invalid ? 'Enter the amount in rupees, like 12345.67' : null}
      />
      <Pressable onPress={onSkip} accessibilityRole="button" accessibilityLabel="Skip for now" hitSlop={8}>
        <Text style={[styles.link, { color: c.muted }]}>Skip for now</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, marginTop: spacing.md, gap: spacing.xs },
  question: { fontSize: 15, fontWeight: '700' },
  help: { fontSize: 13 },
  link: { fontSize: 14, fontWeight: '600', paddingVertical: spacing.xs },
});
