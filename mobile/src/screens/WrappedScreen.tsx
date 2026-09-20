import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api/endpoints';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { radius, spacing, useTheme } from '../theme';

/** Port of frontend/src/pages/Wrapped.tsx, which renders a blank page (`return null`) until its
 *  query resolves and on a failure alike -- a phone user needs the loading and failure states to
 *  be visible instead. */
export function WrappedScreen() {
  // Milestone titles name real goals and net-worth thresholds.
  usePreventScreenCapture();
  const c = useTheme();
  const year = new Date().getFullYear();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['wrapped', year], queryFn: () => dashboardApi.wrapped(year),
  });

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      {isError || !data ? (
        <Text style={[styles.note, { color: c.muted }]}>
          Couldn't load your year in review. Try again later.
        </Text>
      ) : (
        <View style={[styles.card, { backgroundColor: c.ink }]}>
          <Text style={[styles.eyebrow, { color: c.onPrimary }]}>Your Financial Journey</Text>
          <Text accessibilityRole="header" style={[styles.year, { color: c.onPrimary }]}>{data.year}</Text>
          <Text style={[styles.count, { color: c.onPrimary }]}>{data.goalContributions}</Text>
          <Text style={[styles.eyebrow, styles.countCaption, { color: c.onPrimary }]}>
            goal contributions this year
          </Text>
          {data.landmarkTitles.length === 0 ? (
            <Text style={[styles.landmark, { color: c.onPrimary }]}>No milestones reached yet this year.</Text>
          ) : (
            data.landmarkTitles.map((title) => (
              <Text key={title} style={[styles.landmark, { color: c.onPrimary }]}>{title}</Text>
            ))
          )}
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md },
  note: { fontSize: 13 },
  card: { borderRadius: radius.xl, padding: spacing.lg },
  // 0.7 opacity on the caption lines matches the web card's opacity-70.
  eyebrow: { fontSize: 13, opacity: 0.7 },
  year: { fontSize: 36, fontWeight: '700', marginBottom: spacing.md },
  count: { fontSize: 18 },
  countCaption: { marginBottom: spacing.md },
  landmark: { fontSize: 14, marginBottom: spacing.sm },
});
