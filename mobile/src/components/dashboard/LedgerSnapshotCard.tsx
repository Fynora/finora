import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { DashboardCard } from './DashboardCard';
import { DashboardSectionHeader } from './DashboardSectionHeader';
import { DeltaChip } from './DeltaChip';
import { fmtCurrency } from '../../lib/format';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { spacing, typography, useTheme } from '../../theme';

export interface KpiItem {
  label: string;
  /** null only for a percent the backend withheld (Savings Rate) -- shown as a dash with its caption. */
  value: number | null;
  delta: number | null;
  invert: boolean;
  caption: string | null;
  isPercent: boolean;
}

// A half-width tile on a 360 point phone has about 122 points for its number; ten characters of
// Manrope Bold 20 ("₹12,48,320") is the longest that fits. One crore and above gets a full row.
const MAX_HALF_WIDTH_CHARS = 10;

const display = (k: KpiItem) =>
  k.value === null ? '—' : k.isPercent ? `${Math.round(k.value)}%` : fmtCurrency(k.value);

/**
 * The month's figures as a grid of glass tiles (card redesign, 2026-10-10; before that, one card
 * with a row per figure). Same KpiItem shape, same accessibility-label construction and the same
 * good/bad delta logic as the list it replaces.
 *
 * `title` and `deltaLabel` are whatever period the caller was given by the server: this file must
 * never assert a month of its own (Bug 05, guarded by scripts/check-reporting-period-labels.py,
 * which is why the grid lives here and not in a new file that script does not scan). The
 * comparison period is named once, beside the section title, instead of after every figure.
 */
export function LedgerSnapshotCard({
  kpis, title, deltaLabel, deltaSpokenLabel,
}: { kpis: KpiItem[]; title: string; deltaLabel: string; deltaSpokenLabel: string }) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  const anyDelta = kpis.some((k) => k.delta !== null && k.delta !== undefined);
  // One long amount puts every tile on its own row, so the grid never mixes widths.
  const singleColumn = largeText || kpis.some((k) => display(k).length > MAX_HALF_WIDTH_CHARS);

  return (
    <View style={styles.wrap}>
      <DashboardSectionHeader title={title} caption={anyDelta ? deltaLabel : undefined} />
      <View style={styles.grid}>
        {kpis.map((k) => {
          const displayValue = display(k);
          const hasDelta = k.delta !== null && k.delta !== undefined;
          return (
            <DashboardCard
              key={k.label}
              testID={`kpi-tile-${k.label}`}
              padding="compact"
              style={singleColumn ? styles.tileFull : styles.tileHalf}
            >
              <View
                style={styles.tileBody}
                accessible
                accessibilityLabel={
                  hasDelta
                    ? `${k.label}: ${displayValue}, ${k.delta! >= 0 ? 'up' : 'down'} ${Math.abs(k.delta!).toFixed(1)} percent ${deltaSpokenLabel}`
                    : k.caption
                      ? `${k.label}: ${displayValue}, ${k.caption}`
                      : `${k.label}: ${displayValue}`
                }
              >
                <Text style={[typography.eyebrow, { color: c.mutedInk }]} numberOfLines={1}>{k.label}</Text>
                {k.isPercent || k.value === null ? (
                  <Text testID={`kpi-${k.label}`} style={[typography.numberM, { color: c.ink }]}>{displayValue}</Text>
                ) : (
                  <AnimatedNumber testID={`kpi-${k.label}`} value={k.value} style={[typography.numberM, { color: c.ink }]} />
                )}
                {hasDelta ? (
                  <DeltaChip delta={k.delta!} good={k.invert ? k.delta! < 0 : k.delta! >= 0} />
                ) : k.caption ? (
                  <Text style={[typography.caption, { color: c.mutedInk }]}>{k.caption}</Text>
                ) : null}
              </View>
            </DashboardCard>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.ms },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.ms },
  // 47% plus flexGrow fills a two-up row exactly across the 12 point gap at any phone width.
  tileHalf: { flexBasis: '47%', flexGrow: 1 },
  tileFull: { flexBasis: '100%' },
  tileBody: { gap: spacing.sm },
});
