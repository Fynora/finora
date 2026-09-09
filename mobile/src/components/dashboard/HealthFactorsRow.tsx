import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { healthBarColor, healthImprovementSuggestion, healthToneBg, scoreLabel } from '../../lib/health';
import { radius, spacing, useTheme } from '../../theme';

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
 */
export function HealthFactorsRow({
  available, breakdown, breakdownDetail, topOpportunityFactor, topOpportunityPotentialGain,
}: Props) {
  const c = useTheme();
  const [expanded, setExpanded] = useState<string | null>(null);

  if (!available) return null;

  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
      {Object.entries(breakdown).map(([name, score]) => {
        const detail = breakdownDetail[name];
        const isExpanded = expanded === name;
        const isTopOpportunity = name === topOpportunityFactor && topOpportunityPotentialGain !== null;
        return (
          <View key={name} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>
            <View style={styles.headerRow}>
              <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>{name}</Text>
              <View style={[styles.pill, { backgroundColor: healthToneBg(score, c) }]}>
                <Text style={[styles.pillText, { color: healthBarColor(score, c) }]}>{scoreLabel(score)}</Text>
              </View>
            </View>
            <View style={styles.scoreRow}>
              <Text style={[styles.score, { color: c.ink }]}>{Math.round(score)}%</Text>
              {detail ? (
                <Pressable
                  onPress={() => setExpanded((cur) => (cur === name ? null : name))}
                  hitSlop={8}
                  accessibilityRole="button"
                  accessibilityState={{ expanded: isExpanded }}
                  accessibilityLabel={`${name}: ${isExpanded ? 'hide details' : 'why?'}`}
                >
                  <Text style={[styles.why, { color: c.primary }]}>{isExpanded ? 'Hide' : 'Why?'}</Text>
                </Pressable>
              ) : null}
            </View>
            {detail && isExpanded ? <Text style={[styles.detail, { color: c.muted }]}>{detail}</Text> : null}
            <Text style={[styles.suggestion, { color: c.muted }]}>{healthImprovementSuggestion(name, score)}</Text>
            {isTopOpportunity ? (
              <Text style={[styles.opportunity, { color: c.primary }]}>
                ↑ +{topOpportunityPotentialGain} point opportunity
              </Text>
            ) : null}
          </View>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  row: { gap: spacing.sm, paddingVertical: spacing.xs },
  card: { width: 200, borderWidth: 1, borderRadius: radius.lg, padding: spacing.md },
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.xs },
  name: { fontSize: 13, fontWeight: '600', flexShrink: 1 },
  pill: { borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 },
  pillText: { fontSize: 10, fontWeight: '700' },
  scoreRow: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: 6 },
  score: { fontSize: 18, fontWeight: '700' },
  why: { fontSize: 12, fontWeight: '600', textDecorationLine: 'underline' },
  detail: { fontSize: 11, lineHeight: 15, marginTop: 4 },
  suggestion: { fontSize: 11, lineHeight: 15, marginTop: 6 },
  opportunity: { fontSize: 11, fontWeight: '700', marginTop: 6 },
});
