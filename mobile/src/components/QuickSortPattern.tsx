import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { fmtDate } from '../lib/format';
import { patternLine } from '../lib/payeePattern';
import { spacing, useTheme } from '../theme';
import type { QuickSortQuestion } from '../types';

function rupees(n: number) {
  return '₹' + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

/**
 * The pattern line under a Quick sort payee, and the payments it opens (spec
 * docs/superpowers/specs/2026-11-14-smarter-person-question-design.md). Renders nothing when the question
 * carries no pattern, so callers keep their own fallback line. Key it on the question so a new question
 * starts closed. "See all" shows only when {@code onSeeAll} is given (the Import screen has nowhere to go).
 */
export function QuickSortPattern({ question, onSeeAll }: {
  question: QuickSortQuestion;
  onSeeAll?: (payee: string) => void;
}) {
  const c = useTheme();
  const [open, setOpen] = useState(false);
  const line = patternLine(question);
  if (!line) return null;
  const recent = question.recent ?? [];
  const more = (question.allPayments ?? 0) > recent.length;
  return (
    <View>
      <Pressable
        onPress={() => setOpen((v) => !v)}
        accessibilityRole="button"
        accessibilityLabel={line}
        accessibilityState={{ expanded: open }}
        accessibilityHint={open ? 'Hides these payments' : 'Shows these payments'}
        style={styles.line}
      >
        <Text style={[styles.lineText, { color: c.mutedInk }]}>{line}</Text>
        <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
          <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={14} color={c.muted} />
        </View>
      </Pressable>
      {open ? (
        <View style={styles.list}>
          {recent.map((s) => (
            <Text key={s.id} style={[styles.row, { color: c.mutedInk }]}>
              {fmtDate(s.date) ?? s.date} · {rupees(s.amount)} · {s.description}
            </Text>
          ))}
          {more && onSeeAll ? (
            <Pressable onPress={() => onSeeAll(question.payee)} accessibilityRole="button" style={styles.seeAll}>
              <Text style={[styles.seeAllText, { color: c.primary }]}>See all {question.allPayments} in Transactions</Text>
            </Pressable>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  // 44pt tall like the panel's other controls, so the line is a comfortable touch target.
  line: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44, flexWrap: 'wrap' },
  lineText: { fontSize: 12, textDecorationLine: 'underline', textDecorationStyle: 'dotted', flexShrink: 1 },
  list: { gap: 2, marginBottom: spacing.xs },
  row: { fontSize: 12 },
  seeAll: { minHeight: 44, justifyContent: 'center' },
  seeAllText: { fontSize: 13, fontWeight: '600' },
});
