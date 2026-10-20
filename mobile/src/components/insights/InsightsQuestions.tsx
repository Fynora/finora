import { Pressable, StyleSheet, Text } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../Card';
import { spacing, typography, useTheme } from '../../theme';

export type InsightsSection = 'lookAt' | 'comingUp' | 'paidRegularly' | 'withPeople';

interface Props {
  counts: { lookAt: number; comingUp: number; paidRegularly: number; withPeople: number };
  onJump: (section: InsightsSection) => void;
  onOpenReports: () => void;
}

/**
 * The plain questions at the top of Insights, each one a way into the section that answers it. A
 * question whose section is empty is left out; "Where did my money go?" is Reports' question, so it
 * opens Reports instead of jumping.
 */
export function InsightsQuestions({ counts, onJump, onOpenReports }: Props) {
  const c = useTheme();
  const questions: { section: InsightsSection; text: string; count?: number; unit?: [string, string] }[] = [
    { section: 'lookAt', text: 'What needs a look?', count: counts.lookAt },
    { section: 'comingUp', text: "What's coming up?" },
    { section: 'paidRegularly', text: 'What do I pay regularly?' },
    { section: 'withPeople', text: 'Who owes me?', count: counts.withPeople, unit: ['person', 'people'] },
  ];
  const shown = questions.filter((q) => counts[q.section] > 0);
  return (
    <Card style={styles.card}>
      {shown.map((q, i) => (
        <Pressable
          key={q.section}
          onPress={() => onJump(q.section)}
          accessibilityRole="button"
          accessibilityLabel={q.count !== undefined
            ? `${q.text} ${q.count} ${(q.unit ?? ['thing', 'things'])[q.count === 1 ? 0 : 1]}`
            : q.text}
          style={[styles.row, i > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
        >
          <Text style={[typography.labelM, styles.text, { color: c.ink }]}>{q.text}</Text>
          {q.count !== undefined ? <Text style={[typography.labelM, { color: c.mutedInk }]}>{q.count}</Text> : null}
          <Ionicons name="chevron-down" size={16} color={c.mutedInk} />
        </Pressable>
      ))}
      <Pressable
        onPress={onOpenReports}
        accessibilityRole="button"
        accessibilityLabel="Where did my money go? Opens Reports"
        style={[styles.row, shown.length > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
      >
        <Text style={[typography.labelM, styles.text, { color: c.ink }]}>Where did my money go?</Text>
        <Text style={[typography.labelS, { color: c.primary }]}>Reports</Text>
        <Ionicons name="arrow-forward" size={16} color={c.primary} />
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { paddingVertical: spacing.xs },
  // 44pt minimum touch target (Apple HIG).
  row: { minHeight: 44, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm },
  text: { flex: 1 },
});
