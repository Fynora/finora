import { Pressable, StyleSheet, Text, View } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { ProgressBar } from './ProgressBar';
import { healthBarColor, healthImprovementSuggestion, healthLabelInk, scoreLabel } from '../../lib/health';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { spacing, typography, useTheme } from '../../theme';

/**
 * One factor's card, extracted from HealthFactorsRow's own inline `.map()` body (passbook chrome
 * pass -- see docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md's
 * HealthFactorsRow correction). Every piece of logic here (healthImprovementSuggestion,
 * scoreLabel, the Why?/Hide toggle, the top-opportunity highlight) is unchanged from
 * HealthFactorsRow.tsx's previous inline version -- only the outer surface moved from a local
 * bordered View to DashboardCard, and typography was applied.
 *
 * Card redesign (2026-10-10): the tier is a bar plus a written label instead of a tinted pill.
 * The bar takes healthBarColor (a graphic), the label takes healthLabelInk (text), so the
 * neutral "Good" tier no longer needs the solid-graphite special case the pill had.
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
  const largeText = useLargeFontScale();
  return (
    <DashboardCard style={styles.card} padding="compact">
      <View style={styles.headerRow}>
        {/* One line is a fair trade at ordinary sizes; at large ones it cut "Savings Rate" to
            "Savings..." beside the link, which is the factor's whole identity. */}
        <Text style={[typography.labelS, styles.name, { color: c.mutedInk }]} numberOfLines={largeText ? 2 : 1}>{name}</Text>
        {detail ? (
          <Pressable
            onPress={onToggle}
            hitSlop={14}
            accessibilityRole="button"
            accessibilityState={{ expanded: isExpanded }}
            accessibilityLabel={`${name}: ${isExpanded ? 'hide details' : 'why?'}`}
          >
            <Text style={[typography.labelS, styles.why, { color: c.primary }]}>{isExpanded ? 'Hide' : 'Why?'}</Text>
          </Pressable>
        ) : null}
      </View>
      <Text style={[typography.numberM, { color: c.ink }]}>{Math.round(score)}%</Text>
      <ProgressBar percent={score} color={healthBarColor(score, c)} height={4} />
      <Text style={[typography.labelS, { color: healthLabelInk(score, c) }]}>{scoreLabel(score)}</Text>
      {detail && isExpanded ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{detail}</Text> : null}
      <Text style={[typography.bodyS, { color: c.mutedInk }]}>{healthImprovementSuggestion(name, score)}</Text>
      {isTopOpportunity ? (
        <Text style={[typography.labelS, { color: c.brassInk }]}>
          ↑ +{topOpportunityPotentialGain} point opportunity
        </Text>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { width: 200, gap: spacing.sm },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.xs },
  name: { flexShrink: 1 },
  why: { textDecorationLine: 'underline' },
});
