import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { Card } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { spacing, useTheme } from '../../theme';

export interface KpiItem {
  label: string;
  value: number;
  delta: number | null;
  invert: boolean;
  caption: string | null;
  isPercent: boolean;
}

/** Extracted, unchanged, from DashboardScreen.tsx's original 5-item KPI grid so it can render a
 *  4-item subset here (Total Balance moved to AccountsCard) without duplicating the render logic. */
export function MonthlySnapshotGrid({
  kpis, deltaLabel, deltaSpokenLabel,
}: {
  kpis: KpiItem[];
  deltaLabel: string;
  deltaSpokenLabel: string;
}) {
  const c = useTheme();
  return (
    <View style={styles.grid}>
      {kpis.map((k) => {
        const displayValue = k.isPercent ? `${Math.round(k.value)}%` : fmtCurrency(k.value);
        return (
          <Card key={k.label} style={styles.card}>
            <View
              accessible
              accessibilityLabel={
                k.delta !== null && k.delta !== undefined
                  ? `${k.label}: ${displayValue}, ${k.delta >= 0 ? 'up' : 'down'} ${Math.abs(k.delta).toFixed(1)} percent ${deltaSpokenLabel}`
                  : k.caption
                    ? `${k.label}: ${displayValue}, ${k.caption}`
                    : `${k.label}: ${displayValue}`
              }
            >
              <Text style={[styles.label, { color: c.muted }]}>{k.label}</Text>
              {k.isPercent ? (
                <Text testID={`kpi-${k.label}`} style={[styles.value, { color: c.ink }]} numberOfLines={1}>
                  {displayValue}
                </Text>
              ) : (
                <AnimatedNumber testID={`kpi-${k.label}`} value={k.value} style={[styles.value, { color: c.ink }]} />
              )}
              {k.delta !== null && k.delta !== undefined ? (
                <Text style={[styles.delta, { color: (k.invert ? k.delta < 0 : k.delta >= 0) ? c.success : c.danger }]}>
                  {k.delta >= 0 ? '▲' : '▼'} {Math.abs(k.delta).toFixed(1)}% {deltaLabel}
                </Text>
              ) : k.caption ? (
                <Text style={[styles.delta, { color: c.mutedInk }]}>{k.caption}</Text>
              ) : (
                <Text style={styles.delta} />
              )}
            </View>
          </Card>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  card: { width: '48%', flexGrow: 1 },
  label: { fontSize: 12 },
  value: { fontSize: 19, fontWeight: '700', marginTop: 4 },
  delta: { fontSize: 11, marginTop: 2, minHeight: 14 },
});
