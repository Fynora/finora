import { Pressable, StyleSheet, Text, View } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { healthBarColor, healthImprovementSuggestion, scoreLabel } from '../../lib/health';
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
  const barColor = healthBarColor(score, c);
  // "Good" (60-79) is healthBarColor's one NEUTRAL tier -- it returns c.primary (graphite), not a
  // real hue, unlike Excellent/Fair/Needs Attention (green/amber/red). A translucent tint of a
  // near-black color just reads as pale gray, not "a color" -- confirmed from a real screenshot,
  // still looked uncolored even with a border added. Rendering this one tier as a SOLID graphite
  // badge (brand ink, not a washed-out tint) makes it unmistakably a filled, colored badge without
  // inventing a new hue outside the existing palette or touching healthBarColor's shared cutoffs
  // (Categorization Confidence etc. still read the same function the same way).
  const isNeutralTier = barColor === c.primary;
  return (
    <DashboardCard style={styles.card}>
      <View style={styles.headerRow}>
        <Text style={[styles.name, { color: c.ink, fontFamily: fonts.bodySemibold }]} numberOfLines={1}>{name}</Text>
        <View
          style={[
            styles.pill,
            isNeutralTier
              ? { backgroundColor: c.primary, borderWidth: 1, borderColor: c.primary }
              : { backgroundColor: `${barColor}26`, borderWidth: 1, borderColor: barColor },
          ]}
        >
          <Text style={[styles.pillText, { color: isNeutralTier ? c.onPrimary : barColor, fontFamily: fonts.bodyBold }]}>{scoreLabel(score)}</Text>
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
