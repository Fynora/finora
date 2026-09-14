import { useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { SaveStatus } from '../components/AccountUI';
import { Button } from '../components/Button';
import { workspaceApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { radius, spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

/** Threshold moves in 5% steps -- fine-grained enough for a confidence cutoff, and it avoids
 *  pulling in a native slider dependency for one control. */
const THRESHOLD_STEP = 5;

export function SettingsCategorizationScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();

  const workspaceQ = useQuery({ queryKey: ['workspace-settings'], queryFn: () => workspaceApi.getSettings() });
  const savedThreshold = workspaceQ.data?.autoApplyConfidenceThreshold ?? 90;

  const [thresholdDraft, setThresholdDraft] = useState<number | null>(null);
  const threshold = thresholdDraft ?? savedThreshold;
  const intelDirty = threshold !== savedThreshold;

  const [intelSaving, setIntelSaving] = useState(false);
  const [intelJustSaved, confirmIntelSaved] = useTransientFlag();
  const [intelError, setIntelError] = useState<string | null>(null);

  async function saveThreshold() {
    setIntelError(null);
    await singleFlight(async () => {
      setIntelSaving(true);
      try {
        const saved = await workspaceApi.updateSettings({ autoApplyConfidenceThreshold: threshold });
        queryClient.setQueryData(['workspace-settings'], saved);
        setThresholdDraft(null);
        confirmIntelSaved();
      } catch (e) {
        setIntelError(toUserMessage(e, 'Could not save this setting.'));
      } finally {
        setIntelSaving(false);
      }
    });
  }

  function nudgeThreshold(delta: number) {
    setThresholdDraft((v) => Math.max(0, Math.min(100, (v ?? savedThreshold) + delta)));
  }

  if (workspaceQ.isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      {/* eslint-disable-next-line react-native-a11y/no-nested-touchables -- redundant reachability
          is the intended design: a screen reader user can swipe on the container (adjustable
          role) or navigate directly to either Pressable and activate it, same as a sighted user
          tapping. */}
      <View
        style={styles.stepper}
        accessible
        accessibilityRole="adjustable"
        accessibilityLabel="Confidence threshold"
        accessibilityValue={{ min: 0, max: 100, now: threshold }}
        accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
        onAccessibilityAction={(e) => {
          if (e.nativeEvent.actionName === 'increment') nudgeThreshold(THRESHOLD_STEP);
          if (e.nativeEvent.actionName === 'decrement') nudgeThreshold(-THRESHOLD_STEP);
        }}
      >
        <Pressable
          onPress={() => nudgeThreshold(-THRESHOLD_STEP)}
          disabled={threshold <= 0}
          style={[styles.stepButton, { borderColor: c.border }, threshold <= 0 && styles.disabled]}
          accessibilityRole="button"
          accessibilityLabel="Decrease threshold"
        >
          <Text style={[styles.stepButtonText, { color: c.ink }]}>−</Text>
        </Pressable>
        <Text style={[styles.stepValue, { color: c.ink }]}>{threshold}%</Text>
        <Pressable
          onPress={() => nudgeThreshold(THRESHOLD_STEP)}
          disabled={threshold >= 100}
          style={[styles.stepButton, { borderColor: c.border }, threshold >= 100 && styles.disabled]}
          accessibilityRole="button"
          accessibilityLabel="Increase threshold"
        >
          <Text style={[styles.stepButtonText, { color: c.ink }]}>+</Text>
        </Pressable>
      </View>
      <Text style={[styles.hint, { color: c.mutedInk }]}>
        Suggestions at or above this confidence are applied automatically. Anything below it is
        left for you to confirm.
      </Text>
      <Pressable
        onPress={() => navigation.navigate('CategoryReview')}
        style={styles.reviewLink}
        hitSlop={8}
        accessibilityRole="button"
        accessibilityLabel="Review categories"
        accessibilityHint="Opens the transactions waiting for a category"
      >
        <Text style={[styles.reviewLinkText, { color: c.primary }]}>
          Review transactions waiting for a category →
        </Text>
      </Pressable>

      {intelError ? <Text style={[styles.error, { color: c.danger }]}>{intelError}</Text> : null}
      <View style={styles.saveRow}>
        <SaveStatus dirty={intelDirty} saving={intelSaving} justSaved={intelJustSaved} error={false} />
      </View>
      <Button
        label={intelSaving ? 'Saving…' : 'Save setting'}
        onPress={() => void saveThreshold()}
        loading={intelSaving}
        disabled={!intelDirty}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  hint: { fontSize: 11, lineHeight: 16, marginTop: spacing.sm },
  reviewLink: { marginTop: spacing.sm },
  reviewLinkText: { fontSize: 13, fontWeight: '600' },
  error: { fontSize: 13, marginTop: spacing.sm },
  saveRow: { alignItems: 'flex-end', marginVertical: spacing.sm, minHeight: 16 },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  stepButton: {
    width: 48, height: 48, borderWidth: 1, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center',
  },
  stepButtonText: { fontSize: 22, fontWeight: '600', lineHeight: 26 },
  stepValue: { fontSize: 20, fontWeight: '700', minWidth: 64, textAlign: 'center' },
  disabled: { opacity: 0.4 },
});
