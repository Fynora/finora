import { StyleSheet, Text, View } from 'react-native';
import { spacing, typography, useTheme } from '../../theme';

/**
 * A change against the comparison period. `good` is separate from the sign on purpose: a rise in
 * expenses is bad, a rise in income is good, and the caller is the one that knows which it has.
 * The comparison period itself ("vs last month") is rendered by the caller from the label it was
 * given, never here: see scripts/check-reporting-period-labels.py.
 *
 * `align` is where the chip sits in its row: at the start under a tile's number (the default), or
 * at the end when it heads a right-aligned column, as on the cash flow trend card.
 */
export function DeltaChip({
  delta, good, align = 'start', testID,
}: { delta: number; good: boolean; align?: 'start' | 'end'; testID?: string }) {
  const c = useTheme();
  return (
    <View
      testID={testID}
      style={[styles.chip, align === 'end' && styles.end, { backgroundColor: good ? c.successBg : c.dangerBg }]}
    >
      <Text style={[typography.labelS, { color: good ? c.successInk : c.dangerInk }]}>
        {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}%
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  chip: { alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: spacing.sm, paddingVertical: 3 },
  end: { alignSelf: 'flex-end' },
});
