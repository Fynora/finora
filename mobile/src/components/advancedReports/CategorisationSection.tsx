import { StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { analyticsApi } from '../../api/endpoints';
import { monthYear } from '../../lib/advancedReports/advancedFormat';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { LoadFailed } from '../LoadFailed';
import { SectionHeading } from '../Card';
import { SkeletonBarRow, SkeletonGroup } from '../skeletons/Skeletons';

/**
 * How well Fynora knows your merchants. This describes the categorisation engine, not your money,
 * and it does not follow the period: what the engine has learned is its whole history. Fetched only
 * when this tab is open. Confidence is an average over merchants and each row says how many merchants
 * it rests on, so a category backed by one merchant is not read as equally sure as one backed by
 * forty. Learned means Fynora filed it on its own; corrected means you changed it.
 */
export function CategorisationSection() {
  const c = useTheme();
  const confidenceQ = useQuery({ queryKey: ['advanced-reports-confidence'], queryFn: () => analyticsApi.categoryConfidence() });
  const learningQ = useQuery({ queryKey: ['advanced-reports-learning-growth'], queryFn: () => analyticsApi.learningGrowth() });
  const confidence = confidenceQ.data ?? [];
  const learning = learningQ.data ?? [];
  const peak = Math.max(...learning.map((p) => p.learnedCount + p.correctedCount), 1);

  return (
    <View style={styles.stack}>
      <Text style={[typography.bodyS, { color: c.mutedInk }]} testID="categorisation-intro">
        This is about how Fynora sorts your transactions, not about your money, and it does not change with the period: it covers everything it has learned from you.
      </Text>
      <DashboardCard testID="confidence">
        <SectionHeading title="How sure Fynora is" />
        <Text style={[typography.caption, styles.hint, { color: c.mutedInk }]}>The average confidence across the merchants filed under each category.</Text>
        {confidenceQ.isLoading ? (
          <SkeletonGroup style={styles.skeleton}>{[0, 1, 2].map((i) => <SkeletonBarRow key={i} />)}</SkeletonGroup>
        ) : confidenceQ.isError && confidenceQ.data === undefined ? (
          <LoadFailed message="Couldn't load this section." tone="danger" onRetry={() => void confidenceQ.refetch()} />
        ) : confidence.length === 0 ? (
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>Nothing learned yet. Confirm a few categorisations and this fills in.</Text>
        ) : confidence.map((row) => (
          <View key={row.category} style={styles.row} accessible accessibilityLabel={`${row.category}: ${Math.round(row.avgConfidence)} percent, across ${row.merchantCount} merchants`}>
            <View style={styles.line}>
              <Text style={[typography.bodyM, styles.name, { color: c.ink }]} numberOfLines={1}>{row.category}</Text>
              <Text style={[typography.bodyM, { color: c.ink }]}>{Math.round(row.avgConfidence)}%</Text>
            </View>
            <View style={[styles.track, { backgroundColor: c.border }]}><View style={[styles.fill, { backgroundColor: c.primary, width: `${Math.min(100, Math.max(0, row.avgConfidence))}%` }]} /></View>
            <Text style={[typography.caption, { color: c.mutedInk }]}>Across {row.merchantCount} {row.merchantCount === 1 ? 'merchant' : 'merchants'}</Text>
          </View>
        ))}
      </DashboardCard>

      <DashboardCard testID="learning">
        <SectionHeading title="Learned and corrected" />
        <Text style={[typography.caption, styles.hint, { color: c.mutedInk }]}>Each month: categorisations Fynora learned on its own, and ones you corrected.</Text>
        {learningQ.isLoading ? (
          <SkeletonGroup style={styles.skeleton}>{[0, 1, 2].map((i) => <SkeletonBarRow key={i} />)}</SkeletonGroup>
        ) : learningQ.isError && learningQ.data === undefined ? (
          <LoadFailed message="Couldn't load this section." tone="danger" onRetry={() => void learningQ.refetch()} />
        ) : learning.length === 0 ? (
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>No learning history yet. This fills in as you confirm categorisations over time.</Text>
        ) : learning.map((p) => (
          <View key={p.month} style={styles.row} accessible accessibilityLabel={`${monthYear(p.month)}: ${p.learnedCount} learned, ${p.correctedCount} corrected`}>
            <View style={styles.line}>
              <Text style={[typography.bodyS, { color: c.mutedInk }]}>{monthYear(p.month)}</Text>
              <Text style={[typography.caption, { color: c.ink }]}>{p.learnedCount} learned · {p.correctedCount} corrected</Text>
            </View>
            <View style={[styles.track, styles.split, { backgroundColor: c.border }]}>
              <View style={{ backgroundColor: c.success, width: `${(p.learnedCount / peak) * 100}%` }} />
              <View style={{ backgroundColor: c.primary, width: `${(p.correctedCount / peak) * 100}%` }} />
            </View>
          </View>
        ))}
      </DashboardCard>
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: spacing.md },
  hint: { marginBottom: spacing.sm },
  skeleton: { gap: spacing.sm },
  row: { gap: 4, marginBottom: spacing.md },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm },
  name: { flex: 1 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  split: { flexDirection: 'row' },
  fill: { height: '100%', borderRadius: 3 },
});
