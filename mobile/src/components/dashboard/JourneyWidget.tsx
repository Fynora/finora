import { Pressable, StyleSheet, Text } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { dashboardApi } from '../../api/endpoints';
import { mostRecentHighlight } from '../../lib/timeline';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from './DashboardCard';
import { DashboardSectionHeader } from './DashboardSectionHeader';

/** Mobile counterpart of frontend/src/components/JourneyWidget.tsx. Renders nothing until there is
 *  a real Landmark/Major milestone to show, and nothing while loading or on a fetch failure --
 *  a supplementary teaser must never put an error or an empty card on the dashboard. */
export function JourneyWidget({ onViewJourney }: { onViewJourney: () => void }) {
  const c = useTheme();
  const { data: events } = useQuery({ queryKey: ['timeline'], queryFn: () => dashboardApi.timeline() });
  const { data: momentum } = useQuery({
    queryKey: ['timeline', 'momentum'], queryFn: () => dashboardApi.momentum(),
  });

  const highlight = events ? mostRecentHighlight(events) : undefined;
  if (!highlight) return null;

  return (
    <DashboardCard style={styles.card}>
      <DashboardSectionHeader title="Your Journey" />
      <Text style={[typography.labelM, { color: c.ink }]}>{highlight.title}</Text>
      {momentum && momentum.activeMonths > 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>
          Active {momentum.activeMonths} of the last {momentum.windowMonths} months
        </Text>
      ) : null}
      <Pressable onPress={onViewJourney} accessibilityRole="button" style={styles.link}>
        <Text style={[typography.labelS, { color: c.primary }]}>View your journey</Text>
        <Ionicons name="chevron-forward" size={14} color={c.primary} />
      </Pressable>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  // marginTop lives on the card, not on a wrapper in DashboardScreen: the widget renders nothing
  // without a milestone, and a wrapper around nothing would leave its margin behind as a gap.
  card: { marginTop: spacing.md, gap: spacing.sm },
  // The row itself is the 44 point target (it used to rely on hitSlop around a 12 point label).
  link: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44, alignSelf: 'flex-start' },
});
