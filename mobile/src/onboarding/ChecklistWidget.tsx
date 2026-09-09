import { useState } from 'react';
import { Pressable, Text, View, StyleSheet } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../components/Card';
import { onboardingApi } from '../api/endpoints';
import { useTheme } from '../theme';
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
    <Card style={styles.card}>
      <Pressable
        onPress={() => setExpanded((v) => !v)}
        style={styles.header}
        accessibilityRole="button"
        accessibilityState={{ expanded }}
        accessibilityLabel={`Getting Started, ${data.completedCount} of ${data.totalCount} complete`}
      >
        <View>
          <Text style={[styles.title, { color: c.ink }]}>Getting Started</Text>
          <Text style={[styles.progress, { color: c.muted }]}>{data.completedCount} / {data.totalCount} Complete</Text>
        </View>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={18} color={c.muted} />
      </Pressable>
      {expanded ? (
        <View style={styles.body}>
          <View style={[styles.track, { backgroundColor: c.border }]}>
            <View style={[styles.fill, { backgroundColor: c.primary, width: `${percent}%` }]} />
          </View>
          {CHECKLIST_ITEMS.map((item) => (
            <View key={item.key} style={styles.row}>
              <Text style={{ color: c.ink }}>{completedKeys.has(item.key) ? '✅' : '⬜'}</Text>
              <Text style={[styles.label, { color: c.muted }]}>{item.label}</Text>
            </View>
          ))}
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { marginBottom: 16, padding: 16 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  title: { fontSize: 15, fontWeight: '600' },
  progress: { fontSize: 12, marginTop: 2 },
  body: { marginTop: 12 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden', marginBottom: 12 },
  fill: { height: '100%' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 6 },
  label: { fontSize: 14 },
});
