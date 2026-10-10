import { useState } from 'react';
import { Pressable, Text, View, StyleSheet } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { DashboardCard } from '../components/dashboard/DashboardCard';
import { ProgressBar } from '../components/dashboard/ProgressBar';
import { onboardingApi } from '../api/endpoints';
import { spacing, typography, useTheme } from '../theme';
import { CHECKLIST_ITEMS } from './checklistItems';

/**
 * Moved to the very end of the Dashboard's scroll (premium-redesign mockup) and made a
 * collapsed-by-default accordion -- previously always fully expanded at the top of the screen,
 * competing for attention with everything else on first load.
 */
export function ChecklistWidget() {
  const c = useTheme();
  const [expanded, setExpanded] = useState(false);
  const { data } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });

  if (!data || data.completedCount >= data.totalCount) return null;

  const completedKeys = new Set(data.items.filter((i) => i.completed).map((i) => i.key));
  const percent = Math.round((data.completedCount / data.totalCount) * 100);

  return (
    <DashboardCard style={styles.card}>
      <Pressable
        onPress={() => setExpanded((v) => !v)}
        style={styles.header}
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        accessibilityLabel={`Getting Started, ${data.completedCount} of ${data.totalCount} complete`}
      >
        <View style={styles.headerText}>
          <Text style={[typography.cardTitle, { color: c.ink }]}>Getting Started</Text>
          <Text style={[typography.labelS, { color: c.mutedInk }]}>{data.completedCount} / {data.totalCount} Complete</Text>
        </View>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={18} color={c.muted} />
      </Pressable>
      {expanded ? (
        <View style={styles.body}>
          <ProgressBar testID="checklist-progress" percent={percent} color={c.primary} />
          {CHECKLIST_ITEMS.map((item) => {
            const done = completedKeys.has(item.key);
            return (
              // The indicator is a drawing, so the row says in words whether it is done; the rows
              // used to carry a check-mark emoji as text.
              <View key={item.key} style={styles.row} accessible accessibilityLabel={`${item.label}, ${done ? 'done' : 'not done'}`}>
                {/* The tick is onPrimary on success: 3.30:1 in light, 7.87:1 in dark (a graphic
                    needs 3:1). */}
                <View style={[styles.indicator, done ? { backgroundColor: c.success, borderColor: c.success } : { borderColor: c.muted }]}>
                  {done ? <Ionicons name="checkmark" size={14} color={c.onPrimary} /> : null}
                </View>
                <Text style={[typography.bodyM, styles.label, { color: done ? c.mutedInk : c.ink }]}>{item.label}</Text>
              </View>
            );
          })}
        </View>
      ) : null}
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  // The last card on the dashboard: its own top margin (the card can render nothing, so no
  // wrapper carries it) and room under it before the scroll ends.
  card: { marginTop: spacing.md, marginBottom: spacing.md },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm, minHeight: 44 },
  headerText: { flex: 1, gap: 2 },
  body: { marginTop: spacing.ms, gap: spacing.ms },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms },
  indicator: { width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, alignItems: 'center', justifyContent: 'center' },
  label: { flex: 1 },
});
