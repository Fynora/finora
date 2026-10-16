import { StyleSheet, Text, View } from 'react-native';
import type { Observation } from '../../lib/reportSections/observationText';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

/**
 * What is worth noticing about the month: one lead sentence in larger type and up to two more
 * beneath it, as an editorial list rather than a grid of cards. Drawn only when there is something
 * to say; an empty section would be filler.
 */
export function Observations({ items }: { items: Observation[] }) {
  const c = useTheme();
  if (items.length === 0) return null;
  const [lead, ...rest] = items;
  return (
    <DashboardCard testID="observations">
      <SectionHeading title="Worth noticing" />
      <Text testID="observation-lead" style={[typography.bodyM, styles.lead, { color: c.ink }]}>{lead.text}</Text>
      {rest.length > 0 ? (
        <View testID="observation-rest" style={[styles.rest, { borderTopColor: c.border }]}>
          {rest.map((o) => <Text key={o.key} style={[typography.bodyS, { color: c.mutedInk }]}>{o.text}</Text>)}
        </View>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  lead: { fontWeight: '600' },
  rest: { borderTopWidth: StyleSheet.hairlineWidth, marginTop: spacing.sm, paddingTop: spacing.sm, gap: spacing.sm },
});
