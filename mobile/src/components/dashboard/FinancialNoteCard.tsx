import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { fonts, radius, spacing, useTheme } from '../../theme';

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
 */
export function FinancialNoteCard({
  factor, potentialGain, onCreateGoal,
}: {
  factor: string | null;
  potentialGain: number | null;
  onCreateGoal: () => void;
}) {
  const c = useTheme();
  if (!factor || potentialGain === null) return null;

  return (
    <View style={[styles.card, { backgroundColor: c.primaryLight, borderColor: c.brass }]}>
      <Ionicons name="sparkles-outline" size={18} color={c.brassInk} style={styles.icon} />
      <View style={styles.textWrap}>
        <Text style={[styles.headline, { color: c.ink, fontFamily: fonts.bodySemibold }]}>
          {factor} has the most room to improve right now.
        </Text>
        <Text style={[styles.gain, { color: c.muted, fontFamily: fonts.body }]}>
          Potential gain: <Text style={{ color: c.brassInk, fontFamily: fonts.bodyBold }}>+{potentialGain} points</Text>
        </Text>
      </View>
      <Pressable
        onPress={onCreateGoal}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primary }]}
        accessibilityRole="button"
      >
        <Text style={[styles.ctaText, { color: c.onPrimary, fontFamily: fonts.bodyBold }]}>Create Goal</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  icon: { marginTop: 2 },
  textWrap: { flex: 1 },
  headline: { fontSize: 13, lineHeight: 18 },
  gain: { fontSize: 12, marginTop: 4 },
  cta: { minHeight: 36, paddingHorizontal: spacing.md, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', alignSelf: 'center' },
  ctaText: { fontSize: 12 },
});
