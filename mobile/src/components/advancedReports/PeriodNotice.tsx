import { StyleSheet, Text, View } from 'react-native';
import type { AdvancedReport } from '../../api/endpoints';
import { coverageNote, gateNote, leftOutNote, monthYear, periodSpan } from '../../lib/advancedReports/advancedFormat';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';

/**
 * What the period actually is, and what was left out or withheld, in plain sentences: the months
 * covered, how many of the asked-for months exist, a month left out because it is still in progress,
 * and why there is no comparison. The screen never silently mixes periods, so everything that narrows
 * a figure is said here, once.
 */
export function PeriodNotice({ report }: { report: AdvancedReport }) {
  const c = useTheme();
  const { period, comparison } = report;
  if (!period.hasData) return null;
  const lines = [coverageNote(period), leftOutNote(period), gateNote(comparison, period)].filter((l): l is string => !!l);
  const prev = period.previousStartMonth && period.previousEndMonth
    ? (period.previousStartMonth === period.previousEndMonth ? monthYear(period.previousStartMonth) : `${monthYear(period.previousStartMonth)} – ${monthYear(period.previousEndMonth)}`)
    : null;
  const compared = comparison.reliable && prev ? `Compared with ${prev}.` : null;
  return (
    <DashboardCard padding="compact" testID="period-notice">
      <View style={styles.body}>
        <Text style={[typography.labelM, { color: c.ink }]}>
          {periodSpan(period)} · {period.monthCount} {period.monthCount === 1 ? 'month' : 'months'}
        </Text>
        {compared ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{compared}</Text> : null}
        {lines.map((l) => <Text key={l} style={[typography.bodyS, { color: c.mutedInk }]}>{l}</Text>)}
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  body: { gap: spacing.xs },
});
