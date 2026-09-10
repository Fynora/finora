import { Pressable, StyleSheet, Text, View } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { healthBarColor, healthImprovementSuggestion, healthToneBg, scoreLabel } from '../../lib/health';
import { fonts, spacing, useTheme } from '../../theme';

/**
 * One factor's card, extracted from HealthFactorsRow's own inline `.map()` body (passbook chrome
 * pass -- see docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md's
 * HealthFactorsRow correction). Every piece of logic here (healthImprovementSuggestion,
 * scoreLabel, the Why?/Hide toggle, the top-opportunity highlight) is unchanged from
 * HealthFactorsRow.tsx's previous inline version -- only the outer surface moved from a local
 * bordered View to DashboardCard, and typography was applied.
 */
export function FinancialHealthFactorCard({
  name, score, detail, isExpanded, onToggle, isTopOpportunity, topOpportunityPotentialGain,
}: {
  name: string;
  score: number;
  detail: string | undefined;
  isExpanded: boolean;
  onToggle: () => void;
  isTopOpportunity: boolean;
  topOpportunityPotentialGain: number | null;
}) {
  const c = useTheme();
  return (
    <DashboardCard style={styles.card}>
      <View style={styles.headerRow}>
        <Text style={[styles.name, { color: c.ink, fontFamily: fonts.bodySemibold }]} numberOfLines={1}>{name}</Text>
        <View style={[styles.pill, { backgroundColor: healthToneBg(score, c) }]}>
          <Text style={[styles.pillText, { color: healthBarColor(score, c), fontFamily: fonts.bodyBold }]}>{scoreLabel(score)}</Text>
        </View>
      </View>
      <View style={styles.scoreRow}>
        <Text style={[styles.score, { color: c.ink, fontFamily: fonts.displayBold }]}>{Math.round(score)}%</Text>
        {detail ? (
          <Pressable
            onPress={onToggle}
            hitSlop={8}
            accessibilityRole="button"
            accessibilityState={{ expanded: isExpanded }}
            accessibilityLabel={`${name}: ${isExpanded ? 'hide details' : 'why?'}`}
          >
            <Text style={[styles.why, { color: c.primary, fontFamily: fonts.bodySemibold }]}>{isExpanded ? 'Hide' : 'Why?'}</Text>
          </Pressable>
        ) : null}
      </View>
      {detail && isExpanded ? <Text style={[styles.detail, { color: c.muted, fontFamily: fonts.body }]}>{detail}</Text> : null}
      <Text style={[styles.suggestion, { color: c.muted, fontFamily: fonts.body }]}>{healthImprovementSuggestion(name, score)}</Text>
      {isTopOpportunity ? (
        <Text style={[styles.opportunity, { color: c.brassInk, fontFamily: fonts.bodyBold }]}>
          ↑ +{topOpportunityPotentialGain} point opportunity
        </Text>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { width: 200 },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.xs },
  name: { fontSize: 13, flexShrink: 1 },
  pill: { borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 },
  pillText: { fontSize: 10 },
  scoreRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: 6 },
  score: { fontSize: 18 },
  why: { fontSize: 12, textDecorationLine: 'underline' },
  detail: { fontSize: 11, lineHeight: 15, marginTop: 4 },
  suggestion: { fontSize: 11, lineHeight: 15, marginTop: 6 },
  opportunity: { fontSize: 11, marginTop: 6 },
});
