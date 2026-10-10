import { Pressable, StyleSheet, Text, View } from 'react-native';
import { GlassSurface } from '../GlassSurface';
import { IconWell } from './IconWell';
import { useReduceTransparency } from '../../lib/useReduceTransparency';
import { radius, spacing, typography, useTheme } from '../../theme';
import { withAlpha } from '../../theme/glass';

/**
 * Ports frontend/src/pages/Dashboard.tsx's "AI Insight card" -- deliberately built from the
 * backend's own healthTopOpportunityFactor/healthTopOpportunityPotentialGain
 * (DashboardService.computeTopOpportunity: a pure deterministic formula, weight(factor) * (80 -
 * factor's current score), using the exact same weights the overall Health Score is built from --
 * not an LLM call, confirmed by reading that method, not assumed), so the
 * Financial Health -> Opportunity -> Create Goal narrative stays one connected thread with the
 * Hero's own gauge and the Health Factors row's highlighted card, exactly as the web app already
 * does it.
 *
 * Renamed from AIInsightCard and retoned per the passbook redesign's writing guidance: no "AI"
 * framing (the computation was never an LLM to begin with, so the old name overclaimed), calmer
 * factual register instead of "biggest opportunity" sales language. Brass accent on the icon/
 * border and the potential-gain figure -- see docs/superpowers/specs/2026-09-10-dashboard-
 * passbook-redesign-design.md.
 *
 * Card redesign (2026-10-10): brass glass. The card is a GlassSurface tinted with the brass wash
 * at the same alpha as every other glass surface, with a brass edge; glassContrast.test.ts
 * measures its three text colours on that surface at every backdrop pixel.
 */
export function FinancialNoteCard({
  factor, potentialGain, onCreateGoal,
}: {
  factor: string | null;
  potentialGain: number | null;
  onCreateGoal: () => void;
}) {
  const c = useTheme();
  // GlassSurface moves a caller's fill onto its tint layer and uses it as given, so the solid
  // fallback has to be chosen here: opaque brass under Reduce Transparency, or before the setting
  // is known.
  const solid = useReduceTransparency() !== false;
  const wash = solid ? c.brassBg : withAlpha(c.brassBg, c.glassAlpha);
  if (!factor || potentialGain === null) return null;

  return (
    <GlassSurface testID="financial-note" style={[styles.card, { backgroundColor: wash, borderColor: c.brass }]}>
      <IconWell name="sparkles-outline" tone="brass" round size={36} />
      <View style={styles.textWrap}>
        <Text style={[typography.labelM, { color: c.ink }]}>
          {factor} has the most room to improve right now.
        </Text>
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>
          Potential gain: <Text style={[typography.labelS, { color: c.brassInk }]}>+{potentialGain} points</Text>
        </Text>
      </View>
      <Pressable
        onPress={onCreateGoal}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primary }]}
        accessibilityRole="button"
      >
        <Text style={[typography.labelS, { color: c.onPrimary }]}>Create Goal</Text>
      </Pressable>
    </GlassSurface>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.xxl, padding: spacing.md,
    flexDirection: 'row', alignItems: 'center', gap: spacing.ms,
  },
  textWrap: { flex: 1, gap: spacing.xs },
  cta: { minHeight: 44, paddingHorizontal: spacing.md, borderRadius: 999, alignItems: 'center', justifyContent: 'center' },
});
