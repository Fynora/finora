import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { AdvancedInsight } from '../../api/endpoints';
import { drillFilter, type TransactionsFilter } from '../../lib/advancedReports/advancedFormat';
import { spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';

const ICON = { UP: 'arrow-up-outline', DOWN: 'arrow-down-outline', NEUTRAL: 'remove-outline' } as const;
const DIRECTION = { UP: 'Up', DOWN: 'Down', NEUTRAL: 'Note' } as const;

/**
 * What stands out in the period, worded by the server so the web and the app say the same thing.
 * Each carries its figures and the earlier period it is set against, and opens the transactions
 * behind it. The arrow is the direction of the thing described, not a verdict on it, so it is never
 * coloured good or bad; the marker is brass, as on the Insights screen.
 */
export function InsightCards({ insights, onOpen }: { insights: AdvancedInsight[]; onOpen: (filter: TransactionsFilter) => void }) {
  const c = useTheme();
  return (
    <DashboardCard testID="insight-cards">
      <SectionHeading title="Key insights" />
      {insights.length === 0 ? (
        <Text style={[typography.bodyS, { color: c.mutedInk }]} testID="no-insights">
          Nothing stands out yet. Insights appear when there is enough history to compare this period with an earlier one.
        </Text>
      ) : (
        insights.map((i) => {
          const body = (
            <>
              <View style={[styles.marker, { borderColor: c.brass }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
                <Ionicons name={ICON[i.tone] ?? 'remove-outline'} size={16} color={c.brassInk} />
              </View>
              <View style={styles.text}>
                <Text style={[typography.labelM, { color: c.ink }]}>{i.headline}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{i.detail}</Text>
                {i.drill ? <Text style={[typography.labelS, { color: c.primary }]}>See the transactions</Text> : null}
              </View>
            </>
          );
          const label = `${DIRECTION[i.tone] ?? 'Note'}: ${i.headline}. ${i.detail}`;
          return i.drill ? (
            <Pressable
              key={i.id} style={[styles.item, { borderColor: c.border }]} accessibilityRole="button" accessibilityLabel={label}
              accessibilityHint="Opens the transactions behind this"
              onPress={() => onOpen(drillFilter(i.drill!, i.headline))} testID={`insight-${i.id}`}
            >
              {body}
            </Pressable>
          ) : (
            <View key={i.id} style={[styles.item, { borderColor: c.border }]} accessible accessibilityLabel={label} testID={`insight-${i.id}`}>
              {body}
            </View>
          );
        })
      )}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  item: { flexDirection: 'row', gap: spacing.sm, padding: spacing.sm, borderWidth: StyleSheet.hairlineWidth, borderRadius: 14, marginBottom: spacing.sm, minHeight: 44 },
  marker: { width: 32, height: 32, borderRadius: 10, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1, gap: 2 },
});
