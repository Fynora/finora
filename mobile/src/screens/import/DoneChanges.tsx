import { useMemo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery } from '@tanstack/react-query';
import { aiInsightsApi, insightsApi } from '../../api/endpoints';
import { Card, SectionHeading } from '../../components/Card';
import { monthLabelLong } from '../../lib/format';
import { doneLines, type DoneLine } from '../../lib/importDone';
import { ackEntry, isAcked, lookAtRowsFromServer } from '../../lib/insightsFeed';
import { spacing, typography, useTheme } from '../../theme';

const ICON: Record<DoneLine['kind'], keyof typeof Ionicons.glyphMap> = {
  'card-statement': 'document-text-outline',
  'new-recurring': 'repeat',
  'amount-changed': 'swap-vertical',
  'running-high': 'trending-up',
  people: 'people-outline',
};

/**
 * Import's Done step, "{Month} in Fynora" (spec 2026-11-02, Step 3): what Insights' "To look at"
 * now says about the statement's month, up to three lines, each opening Insights on that month.
 * The heading names the month, not "this statement": the rows cover every account. Same queries
 * (keys and calls) as InsightsScreen, so the two share one cache. Says nothing when there is
 * nothing to show or the request fails -- the import itself succeeded either way.
 */
export function DoneChanges({ month, onOpen }: { month: string | null; onOpen: (month: string) => void }) {
  const c = useTheme();
  const lookAtQ = useQuery({
    queryKey: ['ai-insights', 'look-at', month],
    queryFn: () => aiInsightsApi.lookAt(month ?? undefined),
    enabled: month !== null,
    retry: false,
  });
  const acksQ = useQuery({
    queryKey: ['insight-acknowledgements'],
    queryFn: () => insightsApi.acknowledgements(),
    enabled: month !== null,
    retry: false,
  });
  const lines = useMemo(() => {
    if (!lookAtQ.data) return [];
    const acks = new Set((acksQ.data ?? []).map((a) => ackEntry(a.kind, a.itemKey)));
    return doneLines(lookAtRowsFromServer(lookAtQ.data.rows).filter((r) => !isAcked(r, acks)));
  }, [lookAtQ.data, acksQ.data]);

  if (month === null || lines.length === 0) return null;
  return (
    <Card testID="done-changes">
      <SectionHeading title={`${monthLabelLong(month)} in Fynora`} />
      {lines.map((line, i) => (
        <Pressable
          key={line.key}
          onPress={() => onOpen(month)}
          accessibilityRole="button"
          accessibilityLabel={`${line.title}. ${line.detail}. Opens Insights`}
          style={[styles.row, i > 0 && { borderTopColor: c.border, borderTopWidth: StyleSheet.hairlineWidth }]}
        >
          <View style={[styles.icon, { backgroundColor: c.primaryLight }]}>
            <Ionicons name={ICON[line.kind]} size={16} color={c.primary} />
          </View>
          <View style={styles.text}>
            <Text style={[typography.labelM, { color: c.ink }]}>{line.title}</Text>
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>{line.detail}</Text>
          </View>
          <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />
        </Pressable>
      ))}
    </Card>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm },
  icon: { width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1, gap: 2 },
});
