import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { ReportData } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, typography, useTheme } from '../../theme';

/**
 * Money in that is not counted as income yet (a transfer from a person, an unexplained card credit).
 * Said once, here, with one way to sort it out -- never added to income or expense, and never the
 * same thing as a spending category called "Uncategorized". Absent when there is none.
 */
export function ReviewNotice({ report, onReview }: { report: ReportData; onReview: () => void }) {
  const c = useTheme();
  const amount = report.unresolvedInflow ?? 0;
  if (amount <= 0) return null;
  const count = report.unresolvedInflowCount;
  // An older server sends no count, so the sentence still reads whole without one.
  const what = count != null && count > 0
    ? `${count} ${count === 1 ? 'credit' : 'credits'} worth ${fmtCurrency(amount)} ${count === 1 ? "isn't" : "aren't"}`
    : `${fmtCurrency(amount)} received isn't`;
  return (
    <View testID="review-notice" style={[styles.box, { backgroundColor: c.warningBg, borderColor: c.warning }]}>
      <Text style={[typography.bodyS, styles.text, { color: c.warningInk }]}>{what} counted as income yet.</Text>
      <Pressable
        onPress={onReview} accessibilityRole="button" accessibilityLabel="Review money not counted as income yet"
        style={[styles.button, { borderColor: c.brassInk }]} hitSlop={6}
      >
        <Text style={[typography.labelM, { color: c.brassInk }]}>Review money</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
  text: { flexShrink: 1 },
  button: { alignSelf: 'flex-start', borderWidth: 1, borderRadius: radius.md, minHeight: 44, paddingHorizontal: spacing.md, justifyContent: 'center' },
});
