import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type ViewProps } from 'react-native';
import { fmtCurrency } from '../../lib/format';
import { percentChange, signedAmount } from '../../lib/advancedReports/advancedFormat';
import { spacing, typography, useTheme } from '../../theme';

/** "1%" / "<1%": a row with real money in it must not read "0%". */
export const shareText = (pct: number) => (pct < 1 ? '<1%' : `${Math.round(pct)}%`);

/** A bar sized against the largest in its list, never rounding a real amount down to nothing. */
export function ShareBar({ value, max }: { value: number; max: number }) {
  const c = useTheme();
  const width = value > 0 && max > 0 ? Math.max((value / max) * 100, 1.5) : 0;
  return (
    <View style={[styles.track, { backgroundColor: c.border }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      <View style={[styles.fill, { backgroundColor: c.primary, width: `${width}%` }]} />
    </View>
  );
}

/** What a change reads as, spoken: "up 50 percent", never the arrow glyph. */
export function spokenChange(changeAmount: number | null, changePct: number | null, previousAmount: number | null): string | null {
  if (previousAmount == null || changeAmount == null) return null;
  if (previousAmount === 0) return changeAmount === 0 ? null : 'new, nothing in the earlier period';
  const rounded = Math.round(changeAmount);
  if (rounded === 0) return 'no change';
  const pct = changePct == null ? '' : `, ${Math.round(Math.abs(changePct))} percent`;
  return `${rounded > 0 ? 'up' : 'down'} ${fmtCurrency(Math.abs(rounded))}${pct}`;
}

/**
 * How one row of spending moved against the earlier period: "+₹5,000 · ↑ 12%", "New" when it had
 * nothing then, "No change" when it rounds to none. Nothing when the server withheld the comparison
 * (`previousAmount` is null), so a row never shows a change that was not worked out. More spent reads
 * in the danger ink and less in the success ink, but the sign and the arrow say it too.
 */
export function ChangeText({
  previousAmount, changeAmount, changePct,
}: { previousAmount: number | null; changeAmount: number | null; changePct: number | null }) {
  const c = useTheme();
  if (previousAmount == null || changeAmount == null) return null;
  if (previousAmount === 0) {
    return changeAmount === 0 ? null : <Text style={[typography.caption, { color: c.mutedInk }]}>New, nothing earlier</Text>;
  }
  const rounded = Math.round(changeAmount);
  if (rounded === 0) return <Text style={[typography.caption, { color: c.mutedInk }]}>No change</Text>;
  const pct = percentChange(changePct);
  return (
    <Text style={[typography.caption, { color: rounded > 0 ? c.dangerInk : c.successInk }]}>
      {signedAmount(rounded, fmtCurrency)}{pct ? ` · ${pct}` : ''}
    </Text>
  );
}

/** A label, its amount and share on one line, with the bar and a line of detail under it. */
export function RankedRow({
  label, amount, share, detail, change, bar, onPress, accessibilityLabel, leading, trailing, testID,
}: {
  label: string; amount: number; share: number; detail: string; change?: ReactNode; bar: number;
  onPress?: () => void; accessibilityLabel: string; leading?: ReactNode; trailing?: ReactNode; testID?: string;
}) {
  const c = useTheme();
  const Wrapper = onPress ? PressableRow : PlainRow;
  return (
    <Wrapper onPress={onPress} accessibilityLabel={accessibilityLabel} testID={testID}>
      {leading}
      <View style={styles.rowBody}>
        <View style={styles.rowTop}>
          <Text style={[typography.bodyM, styles.rowLabel, { color: c.ink }]} numberOfLines={1}>{label}</Text>
          <Text style={[typography.bodyM, { color: c.ink }]}>
            {fmtCurrency(amount)} <Text style={[typography.caption, { color: c.mutedInk }]}>{shareText(share)}</Text>
          </Text>
        </View>
        <ShareBar value={amount} max={bar} />
        <View style={styles.rowBottom}>
          <Text style={[typography.caption, { color: c.mutedInk }]}>{detail}</Text>
          <View style={styles.rowChange}>{change}{trailing}</View>
        </View>
      </View>
    </Wrapper>
  );
}


function PlainRow({ children, accessibilityLabel, testID }: ViewProps & { onPress?: () => void; accessibilityLabel: string }) {
  return <View style={styles.row} accessible accessibilityLabel={accessibilityLabel} testID={testID}>{children}</View>;
}

function PressableRow({ children, onPress, accessibilityLabel, testID }: ViewProps & { onPress?: () => void; accessibilityLabel: string }) {
  return (
    <Pressable
      style={styles.row} onPress={onPress} accessibilityRole="button" accessibilityLabel={accessibilityLabel}
      accessibilityHint="Opens the transactions behind this" testID={testID}
    >
      {children}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  row: { flexDirection: 'row', gap: spacing.sm, alignItems: 'flex-start', marginBottom: spacing.md, minHeight: 44 },
  rowBody: { flex: 1, gap: 4 },
  rowTop: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm },
  rowLabel: { flex: 1 },
  rowBottom: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm, alignItems: 'center' },
  rowChange: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
});
