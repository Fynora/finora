import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card, SectionHeading } from '../Card';
import { RecurringQuestion } from '../RecurringQuestion';
import { fmtCurrency, monthLabelLong } from '../../lib/format';
import type { LookAtRow } from '../../lib/insightsFeed';
import { radius, spacing, typography, useTheme } from '../../theme';

interface Props {
  rows: LookAtRow[];
  /** The reporting month, e.g. "September 2026", for the empty line. */
  monthLong: string;
  onOpenRow: (row: LookAtRow) => void;
  onAck: (row: LookAtRow) => void;
  onUpload: () => void;
}

function cardPhrase(row: Extract<LookAtRow, { kind: 'card-statement' }>): string {
  if (!row.cardName) return 'a credit card';
  // The account's own name, often just its bank's ("Axis Bank"): say "card" unless it already does.
  const name = /\bcard\b/i.test(row.cardName) ? row.cardName : `${row.cardName} card`;
  return row.cardLast4 ? `your ${name} ending ${row.cardLast4}` : `your ${name}`;
}

/**
 * "To look at": what changed and needs the user, each row something they can act on. Every row's
 * buttons are siblings of its tappable text, never inside it -- an accessible Pressable hides the
 * buttons nested in it from screen readers.
 */
export function LookAtSection({ rows, monthLong, onOpenRow, onAck, onUpload }: Props) {
  const c = useTheme();
  const divider = { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border };

  function gotIt(row: LookAtRow, name: string) {
    return (
      <Pressable
        onPress={() => onAck(row)}
        accessibilityRole="button"
        accessibilityLabel={`Got it: ${name}`}
        style={[styles.chip, { borderColor: c.border }]}
      >
        <Text style={[typography.labelS, { color: c.ink }]}>Got it</Text>
      </Pressable>
    );
  }

  function renderRow(row: LookAtRow, index: number) {
    const style = [styles.row, index > 0 && divider];
    switch (row.kind) {
      case 'card-statement': {
        const month = monthLabelLong(row.month);
        return (
          <View key="card-statement" style={style}>
            <View style={styles.rowHead}>
              <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
                <Ionicons name="document-text-outline" size={16} color={c.warningInk} />
              </View>
              <Text style={[typography.bodyM, styles.text, { color: c.ink }]}>
                {`You paid ${fmtCurrency(row.total)} toward ${cardPhrase(row)} in ${month} with no card statement uploaded for it. Upload it to see what you bought.`}
              </Text>
            </View>
            <View style={styles.actions}>
              <Pressable
                onPress={onUpload}
                accessibilityRole="button"
                accessibilityLabel="Upload statement"
                style={[styles.chip, { backgroundColor: c.primary, borderColor: c.primary }]}
              >
                <Text style={[typography.labelS, { color: c.onPrimary }]}>Upload statement</Text>
              </Pressable>
              {gotIt(row, `card statement for ${month}`)}
            </View>
          </View>
        );
      }
      case 'new-recurring':
        return (
          <View key={`new-${row.item.merchant}`} style={style}>
            <View style={styles.rowHead}>
              <View style={[styles.icon, { backgroundColor: c.primaryLight }]}>
                <Ionicons name="repeat" size={16} color={c.primary} />
              </View>
              <Text style={[typography.labelM, styles.text, { color: c.ink }]}>{`New repeating payment: ${row.item.merchant}`}</Text>
            </View>
            <RecurringQuestion
              merchant={row.item.merchant}
              state="NEEDS_ANSWER"
              answer={null}
              amount={row.item.averageAmount}
              label={row.item.label}
            />
          </View>
        );
      case 'amount-changed':
        return (
          <View key={`changed-${row.merchant}`} style={style}>
            <Pressable
              onPress={() => onOpenRow(row)}
              accessibilityRole="button"
              accessibilityLabel={`Show the payments to ${row.merchant}`}
              style={styles.rowHead}
            >
              <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
                <Ionicons name="swap-vertical" size={16} color={c.warningInk} />
              </View>
              <Text style={[typography.labelM, styles.text, { color: c.ink }]}>{`Amount changed: ${row.merchant}`}</Text>
              <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />
            </Pressable>
            <RecurringQuestion merchant={row.merchant} state="AMOUNT_CHANGED" answer={row.category || null} amount={row.latestAmount} />
            <View style={styles.actions}>
              {gotIt(row, row.merchant)}
            </View>
          </View>
        );
      case 'running-high': {
        const title = `${row.category} is ${fmtCurrency(row.current - row.priorAverage)} above your usual month`;
        const detail = `${fmtCurrency(row.current)} vs ${fmtCurrency(row.priorAverage)}`;
        return (
          <View key={`high-${row.category}`} style={style}>
            <Pressable
              onPress={() => onOpenRow(row)}
              accessibilityRole="button"
              accessibilityLabel={`${title}, ${detail}. Shows the payments`}
              style={styles.rowHead}
            >
              <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
                <Ionicons name="trending-up" size={16} color={c.warningInk} />
              </View>
              <View style={styles.text}>
                <Text style={[typography.labelM, { color: c.ink }]}>{title}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{detail}</Text>
              </View>
              <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />
            </Pressable>
            <View style={styles.actions}>
              {gotIt(row, row.category)}
            </View>
          </View>
        );
      }
    }
  }

  return (
    <View>
      <SectionHeading
        title="To look at"
        action={rows.length > 0 ? <Text style={[typography.labelM, { color: c.mutedInk }]}>{rows.length}</Text> : undefined}
      />
      <Card>
        {rows.length === 0 ? (
          <Text style={[typography.bodyM, styles.empty, { color: c.mutedInk }]}>{monthLong ? `Nothing new to look at for ${monthLong}.` : 'Nothing new to look at.'}</Text>
        ) : (
          rows.map(renderRow)
        )}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { paddingVertical: spacing.sm, gap: spacing.xs },
  rowHead: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 44 },
  icon: { width: 28, height: 28, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, paddingLeft: 28 + spacing.sm },
  // 44pt minimum touch target (Apple HIG), as RecurringQuestion's chips.
  chip: {
    minHeight: 44, paddingHorizontal: spacing.md, borderWidth: 1, borderRadius: radius.xl,
    alignItems: 'center', justifyContent: 'center',
  },
  empty: { paddingVertical: spacing.sm },
});
