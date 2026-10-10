import { useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { FinancialHealthFactorCard } from './FinancialHealthFactorCard';
import { cardShadowRoom, spacing } from '../../theme';

interface Props {
  available: boolean;
  breakdown: Record<string, number>;
  breakdownDetail: Record<string, string>;
  topOpportunityFactor: string | null;
  topOpportunityPotentialGain: number | null;
}

/**
 * Horizontal-scroll factor cards -- the user's explicit request to keep the Financial Health
 * breakdown visible and prominent ("one of Fynora's most unique features"). Keeps the existing
 * per-row "Why?"/"Hide" detail-disclosure interaction pinned by
 * DashboardScreen.test.tsx's "shows the score and breakdown once available..." test, while adding
 * web's healthImprovementSuggestion copy and the top-opportunity highlight on top of it.
 *
 * Passbook chrome pass: the per-card render used to live inline in this file's own `.map()`; it
 * now lives in FinancialHealthFactorCard, unchanged in logic, restyled onto DashboardCard. This
 * component just owns the `expanded` toggle state and maps breakdown entries to that component.
 */
export function HealthFactorsRow({
  available, breakdown, breakdownDetail, topOpportunityFactor, topOpportunityPotentialGain,
}: Props) {
  const [expanded, setExpanded] = useState<string | null>(null);

  if (!available) return null;

  return (
    <ScrollView testID="health-factors-row" horizontal showsHorizontalScrollIndicator={false} style={styles.scroller} contentContainerStyle={styles.row}>
      {Object.entries(breakdown).map(([name, score]) => (
        <FinancialHealthFactorCard
          key={name}
          name={name}
          score={score}
          detail={breakdownDetail[name]}
          isExpanded={expanded === name}
          onToggle={() => setExpanded((cur) => (cur === name ? null : name))}
          isTopOpportunity={name === topOpportunityFactor && topOpportunityPotentialGain !== null}
          topOpportunityPotentialGain={topOpportunityPotentialGain}
        />
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  // The row runs edge to edge: the negative margin cancels DashboardScreen's side padding
  // (styles.content, spacing.ml) and the content padding puts the first tile back on the screen's
  // left edge, so tiles scroll off under the bezel instead of being cut at the padding line.
  //
  // Vertically the content is padded by the tiles' shadow reach, because a scroll view clips to
  // its bounds, and the scroller's margins take the same amount back: the tiles still sit
  // spacing.md + spacing.xs under the hero and spacing.xs above whatever follows.
  scroller: {
    marginTop: spacing.md + spacing.xs - cardShadowRoom.top,
    marginBottom: spacing.xs - cardShadowRoom.bottom,
    marginHorizontal: -spacing.ml,
  },
  row: {
    gap: spacing.ms,
    paddingTop: cardShadowRoom.top,
    paddingBottom: cardShadowRoom.bottom,
    paddingHorizontal: spacing.ml,
  },
});
