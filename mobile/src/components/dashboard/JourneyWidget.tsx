import { Pressable, StyleSheet, Text } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../../api/endpoints';
import { mostRecentHighlight } from '../../lib/timeline';
import { spacing, useTheme } from '../../theme';
import { Card, SectionHeading } from '../Card';

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
    <Card>
      <SectionHeading title="Your Journey" />
      <Text style={[styles.title, { color: c.ink }]}>{highlight.title}</Text>
      {momentum && momentum.activeMonths > 0 ? (
        <Text style={[styles.momentum, { color: c.muted }]}>
          Active {momentum.activeMonths} of the last {momentum.windowMonths} months
        </Text>
      ) : null}
      <Pressable onPress={onViewJourney} hitSlop={8} accessibilityRole="button" style={styles.link}>
        <Text style={[styles.linkText, { color: c.primary }]}>View your journey</Text>
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: 14 },
  momentum: { fontSize: 12, marginTop: spacing.xs },
  link: { marginTop: spacing.sm, alignSelf: 'flex-start' },
  linkText: { fontSize: 12, fontWeight: '600' },
});
