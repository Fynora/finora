import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Ports frontend/src/pages/Dashboard.tsx's "AI Insight card" -- deliberately built from the
 * backend's own healthTopOpportunityFactor/healthTopOpportunityPotentialGain (a real, deterministic
 * computation, not an LLM call and not the first of the arbitrary insights sentences), so the
 * Financial Health -> Opportunity -> Create Goal narrative stays one connected thread with the
 * Hero's own gauge and the Health Factors row's highlighted card, exactly as the web app already
 * does it.
 */
export function AIInsightCard({
  factor, potentialGain, onCreateGoal,
}: {
  factor: string | null;
  potentialGain: number | null;
  onCreateGoal: () => void;
}) {
  const c = useTheme();
  if (!factor || potentialGain === null) return null;

  return (
    <View style={[styles.card, { backgroundColor: c.primaryLight, borderColor: c.primary }]}>
      <Ionicons name="sparkles-outline" size={18} color={c.primary} style={styles.icon} />
      <View style={styles.textWrap}>
        <Text style={[styles.headline, { color: c.ink }]}>
          Your {factor.toLowerCase()} is the biggest opportunity to improve your score.
        </Text>
        <Text style={[styles.gain, { color: c.muted }]}>
          Potential gain: <Text style={{ color: c.primary, fontWeight: '700' }}>+{potentialGain} points</Text>
        </Text>
      </View>
      <Pressable
        onPress={onCreateGoal}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primary }]}
        accessibilityRole="button"
      >
        <Text style={[styles.ctaText, { color: c.onPrimary }]}>Create Goal</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.md, flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm },
  icon: { marginTop: 2 },
  textWrap: { flex: 1 },
  headline: { fontSize: 13, fontWeight: '600', lineHeight: 18 },
  gain: { fontSize: 12, marginTop: 4 },
  cta: { minHeight: 36, paddingHorizontal: spacing.md, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', alignSelf: 'center' },
  ctaText: { fontSize: 12, fontWeight: '700' },
});
