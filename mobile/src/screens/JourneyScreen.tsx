import { ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQuery } from '@tanstack/react-query';
import { Card, EmptyState } from '../components/Card';
import { dashboardApi } from '../api/endpoints';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { badgeForEvent, groupByYear } from '../lib/timeline';
import { radius, spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'Journey'>;

/** Port of frontend/src/pages/Timeline.tsx. Also the only way into WrappedScreen: the web page has
 *  no link to its /app/wrapped route from anywhere, which would leave a mobile port unreachable. */
export function JourneyScreen({ navigation }: Props) {
  // Milestone titles name real goals and net-worth thresholds.
  usePreventScreenCapture();
  const c = useTheme();
  const { data, isLoading, isError, isFetching, refetch } = useQuery({
    queryKey: ['timeline'], queryFn: () => dashboardApi.timeline(),
  });

  // Gate on isLoading, not on `data` being empty: data is undefined while the query is in flight,
  // which would flash "Your journey starts here" for every user, even one with a full history.
  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  const groups = data ? groupByYear(data) : [];

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl refreshing={isFetching && !isLoading} onRefresh={() => void refetch()} tintColor={c.primary} />
      }
    >
      {isError ? (
        <Text style={[styles.note, { color: c.muted }]}>
          Couldn't load your journey. Pull down to try again.
        </Text>
      ) : groups.length === 0 ? (
        <Card>
          <EmptyState message="Your journey starts here. Milestones you reach will show up on this page." />
        </Card>
      ) : (
        groups.map(([year, events]) => (
          <Card key={year}>
            <Text accessibilityRole="header" style={[styles.year, { color: c.ink }]}>{year}</Text>
            {events.map((e) => {
              const badge = badgeForEvent(e);
              return (
                <View key={e.eventType + e.occurredAt} style={styles.event}>
                  <Text style={[styles.eventTitle, { color: c.ink }]}>{e.title}</Text>
                  {e.detail ? <Text style={[styles.detail, { color: c.muted }]}>{e.detail}</Text> : null}
                  {badge ? (
                    <View style={[styles.badge, { backgroundColor: c.primaryLight }]}>
                      <Text style={[styles.badgeText, { color: c.primary }]}>{badge}</Text>
                    </View>
                  ) : null}
                </View>
              );
            })}
          </Card>
        ))
      )}

      <Pressable
        onPress={() => navigation.navigate('Wrapped')}
        hitSlop={8}
        accessibilityRole="button"
        style={styles.wrappedLink}
      >
        <Text style={[styles.wrappedLinkText, { color: c.primary }]}>See your year in review</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md, gap: spacing.md },
  note: { fontSize: 13 },
  year: { fontSize: 16, fontWeight: '600', marginBottom: spacing.sm },
  event: { marginBottom: spacing.sm },
  eventTitle: { fontSize: 14, fontWeight: '500' },
  detail: { fontSize: 12, marginTop: 2 },
  badge: { alignSelf: 'flex-start', marginTop: spacing.xs, borderRadius: radius.lg, paddingHorizontal: 8, paddingVertical: 2 },
  badgeText: { fontSize: 11, fontWeight: '600' },
  wrappedLink: { alignSelf: 'flex-start' },
  wrappedLinkText: { fontSize: 13, fontWeight: '600' },
});
