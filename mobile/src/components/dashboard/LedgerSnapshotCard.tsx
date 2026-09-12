import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { DashboardCard } from './DashboardCard';
import { SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { fonts, spacing, useTheme } from '../../theme';

export interface KpiItem {
  label: string;
  value: number;
  delta: number | null;
  invert: boolean;
  caption: string | null;
  isPercent: boolean;
}

/**
 * Replaces MonthlySnapshotGrid's 4-separate-cards grid with one "This Month" list -- passbook
 * framing (a ledger entry per line) instead of a dashboard-widget grid. Same KpiItem shape, same
 * accessibility-label construction, and the same delta color logic as the component this
 * replaces (copied verbatim -- this is a layout change, not a logic change).
 */
export function LedgerSnapshotCard({
  kpis, deltaLabel, deltaSpokenLabel,
}: { kpis: KpiItem[]; deltaLabel: string; deltaSpokenLabel: string }) {
  const c = useTheme();
  return (
    <DashboardCard>
      <SectionHeading title="This Month" />
      {kpis.map((k, i) => {
        const displayValue = k.isPercent ? `${Math.round(k.value)}%` : fmtCurrency(k.value);
        return (
          <View
            key={k.label}
            style={[styles.row, i > 0 && { borderTopColor: c.border, borderTopWidth: StyleSheet.hairlineWidth }]}
            accessible
            accessibilityLabel={
              k.delta !== null && k.delta !== undefined
                ? `${k.label}: ${displayValue}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                : k.caption
                  ? `${k.label}: ${displayValue}, ${k.caption}`
                  : `${k.label}: ${displayValue}`
            }
          >
            <Text style={[styles.label, { color: c.mutedInk, fontFamily: fonts.body }]}>{k.label}</Text>
            <View style={styles.valueCol}>
              {k.isPercent ? (
                <Text testID={`kpi-${k.label}`} style={[styles.value, { color: c.ink, fontFamily: fonts.displayBold }]}>
                  {displayValue}
                </Text>
              ) : (
                <AnimatedNumber
                  testID={`kpi-${k.label}`}
                  value={k.value}
                  style={[styles.value, { color: c.ink, fontFamily: fonts.displayBold }]}
                />
              )}
              {k.delta !== null && k.delta !== undefined ? (
                <Text style={[styles.delta, { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger, fontFamily: fonts.bodySemibold }]}>
                  {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                </Text>
              ) : k.caption ? (
                <Text style={[styles.delta, { color: c.mutedInk, fontFamily: fonts.body }]}>{k.caption}</Text>
              ) : null}
            </View>
          </View>
        );
      })}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: spacing.sm },
  label: { fontSize: 13 },
  valueCol: { alignItems: 'flex-end' },
  value: { fontSize: 17 },
  delta: { fontSize: 11, marginTop: 2 },
});
