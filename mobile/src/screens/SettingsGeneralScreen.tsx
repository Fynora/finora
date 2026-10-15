import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { SaveStatus } from '../components/AccountUI';
import { AppSwitch } from '../components/AppSwitch';
import { Button } from '../components/Button';
import { GlassScreen } from '../components/GlassScreen';
import { LoadFailed } from '../components/LoadFailed';
import { SettingsGeneralSkeleton } from '../components/skeletons/ScreenSkeletons';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TextField } from '../components/TextField';
import { userApi, onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { toUserMessage } from '../lib/apiError';
import {
  hapticError, hapticSelectionIfChanged, hapticSuccess, hapticWarning, setHapticsEnabled, useHapticsEnabled,
} from '../lib/haptics';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, THEME_LABEL, THEME_SETTINGS, useTheme, useThemeSetting } from '../theme';
import { NotificationPreferencesSection } from './settings/NotificationPreferencesSection';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';

/**
 * Falls back to a curated list where Intl.supportedValuesOf is unavailable, rather than leaving
 * the picker empty. Hermes ships full ICU on current React Native, so the full list is the normal
 * path; the fallback covers older engines.
 */
function availableTimezones(): string[] {
  try {
    const values = (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone');
    if (Array.isArray(values) && values.length > 0) return values;
  } catch {
    // fall through
  }
  return [
    'Asia/Kolkata', 'UTC', 'America/New_York', 'America/Chicago', 'America/Denver',
    'America/Los_Angeles', 'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Asia/Dubai',
    'Asia/Singapore', 'Asia/Tokyo', 'Asia/Shanghai', 'Australia/Sydney',
  ];
}

export function SettingsGeneralScreen() {
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const { setting: themeSetting, setSetting: setThemeSetting } = useThemeSetting();
  const hapticsOn = useHapticsEnabled();
  const { setOnboardingCompleted } = useAuth();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();

  const userQ = useQuery({ queryKey: ['user-settings'], queryFn: () => userApi.get() });
  const user = userQ.data;

  const [lowBalanceDraft, setLowBalanceDraft] = useState<string | null>(null);
  const [timezoneDraft, setTimezoneDraft] = useState<string | null>(null);
  const [timezonePickerOpen, setTimezonePickerOpen] = useState(false);
  const [prefsSaving, setPrefsSaving] = useState(false);
  const [prefsJustSaved, confirmPrefsSaved] = useTransientFlag();
  const [prefsError, setPrefsError] = useState<string | null>(null);

  const [retakingTour, setRetakingTour] = useState(false);
  const [retakeTourError, setRetakeTourError] = useState<string | null>(null);

  const savedLowBalance = user ? String(user.lowBalanceThreshold) : '';
  const savedTimezone = user?.timezone ?? '';
  const lowBalance = lowBalanceDraft ?? savedLowBalance;
  const timezone = timezoneDraft ?? savedTimezone;
  const prefsDirty = lowBalanceDraft !== null || timezoneDraft !== null;

  const [timezones] = useState<string[]>(availableTimezones);

  async function savePreferences() {
    const amount = parsePositiveAmount(lowBalance);
    if (amount === null) {
      hapticWarning();
      setPrefsError('Low balance alert must be a number greater than zero.');
      return;
    }
    setPrefsError(null);
    await singleFlight(async () => {
      setPrefsSaving(true);
      const startedAt = requestStartedAt();
      try {
        const updated = await userApi.update({ lowBalanceThreshold: amount, timezone });
        hapticSuccess();
        queryClient.setQueryData(['user-settings'], updated);
        // Also re-reads the profile: that is what tells the change watch this edit was ours (see lib/changeSync.ts).
        void queryClient.invalidateQueries({ queryKey: ['user-settings'] });
        setLowBalanceDraft(null);
        setTimezoneDraft(null);
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
        confirmPrefsSaved();
      } catch (e) {
        hapticError();
        reportTransportFailure(e, 'settings-general:save-preferences', startedAt);
        setPrefsError(toUserMessage(e, 'Could not save your preferences.'));
      } finally {
        setPrefsSaving(false);
      }
    });
  }

  async function retakeTour() {
    setRetakeTourError(null);
    await singleFlight(async () => {
      setRetakingTour(true);
      const startedAt = requestStartedAt();
      try {
        await onboardingApi.reset();
        setOnboardingCompleted(false);
      } catch (e) {
        reportTransportFailure(e, 'settings-general:retake-tour', startedAt);
        setRetakeTourError(toUserMessage(e, 'Could not restart the tour.'));
      } finally {
        setRetakingTour(false);
      }
    });
  }

  if (userQ.isLoading) {
    return (
      <GlassScreen style={styles.flex}>
        <ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}>
          <SettingsGeneralSkeleton />
        </ScrollView>
      </GlassScreen>
    );
  }

  // Bug found in a fresh review pass: without this branch, a load failure fell through to the
  // form below with `user` undefined -- every field silently rendered blank (via the `?? ''`
  // fallbacks in savedLowBalance/savedTimezone) with no indication anything had gone wrong,
  // instead of the explicit error message the pre-redesign monolith showed for this exact case.
  // Only when there is nothing to show: a refetch that fails after a good load keeps the content
  // already on screen (React Query keeps its data) instead of hiding it behind this message.
  if (!user) {
    return (
      <GlassScreen style={styles.centered}>
        <LoadFailed variant="screen" message="Couldn't load your settings — please try again later." onRetry={() => void userQ.refetch()} />
      </GlassScreen>
    );
  }

  return (
    // GlassScreen wraps the ScrollView rather than living inside it: the mesh backdrop must stay
    // put while the content scrolls over it.
    <GlassScreen style={styles.flex}>
    <ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }} keyboardShouldPersistTaps="handled">
      <TextField
        label="Low balance alert"
        value={lowBalance}
        onChangeText={setLowBalanceDraft}
        keyboardType="decimal-pad"
        placeholder="2000"
      />

      <Text style={[styles.fieldLabel, { color: c.muted }]}>Timezone</Text>
      <Pressable
        onPress={() => setTimezonePickerOpen(true)}
        style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
        accessibilityRole="button"
        accessibilityLabel={`Timezone: ${timezone || 'not set'}. Change`}
      >
        <Text style={[styles.pickerText, { color: c.ink }]} numberOfLines={1}>{timezone}</Text>
        <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
      </Pressable>

      <Text style={[styles.fieldLabel, { color: c.muted, marginTop: spacing.md }]}>Theme</Text>
      <View style={[styles.segments, { borderColor: c.border }]}>
        {THEME_SETTINGS.map((option) => {
          const active = themeSetting === option;
          return (
            <Pressable
              key={option}
              onPress={() => { hapticSelectionIfChanged(themeSetting, option); setThemeSetting(option); }}
              style={[styles.segment, active && { backgroundColor: c.primaryLight }]}
              accessibilityRole="button"
              accessibilityState={{ selected: active }}
              accessibilityLabel={`${THEME_LABEL[option]} theme`}
            >
              <Text style={[styles.segmentText, { color: active ? c.primary : c.muted }]}>
                {THEME_LABEL[option]}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <Text style={[styles.hint, { color: c.mutedInk }]}>
        Theme applies instantly. The alert amount and timezone save when you tap Save.
      </Text>

      {prefsError ? <Text style={[styles.error, { color: c.dangerInk }]}>{prefsError}</Text> : null}
      <View style={styles.saveRow}>
        <SaveStatus dirty={prefsDirty} saving={prefsSaving} justSaved={prefsJustSaved} error={false} />
      </View>
      <Button
        label={prefsSaving ? 'Saving…' : 'Save preferences'}
        onPress={() => void savePreferences()}
        loading={prefsSaving}
        disabled={!prefsDirty}
      />

      <NotificationPreferencesSection />

      {retakeTourError ? <Text style={[styles.error, { color: c.dangerInk }]}>{retakeTourError}</Text> : null}
      <View style={[styles.retakeTourRow, { borderTopColor: c.border }]}>
        <View style={styles.retakeTourText}>
          <Text style={[styles.fieldLabel, { color: c.ink, marginTop: 0 }]}>Retake Product Tour</Text>
          <Text style={[styles.hint, { color: c.mutedInk }]}>Replay the onboarding experience anytime.</Text>
        </View>
        <Button label="Retake Tour" onPress={() => void retakeTour()} loading={retakingTour} variant="link" />
      </View>

      {/* Applies at once, like Retake Tour above, so it sits here rather than in the block that
          Save preferences saves. Kept on the phone, not on the account (src/lib/haptics.ts). */}
      <View style={[styles.retakeTourRow, { borderTopColor: c.border }]}>
        <View style={styles.retakeTourText}>
          <Text style={[styles.fieldLabel, { color: c.ink, marginTop: 0 }]}>Haptics</Text>
          <Text style={[styles.hint, { color: c.mutedInk }]}>
            Small vibrations when you tap, save or pull to refresh. Your phone&apos;s own settings still apply.
          </Text>
        </View>
        <AppSwitch
          silent
          value={hapticsOn}
          onValueChange={setHapticsEnabled}
          accessibilityLabel="Haptics"
          trackColor={{ true: c.primary, false: c.border }}
          thumbColor={hapticsOn ? c.onPrimary : undefined}
        />
      </View>

      <OptionPickerModal
        visible={timezonePickerOpen}
        title="Timezone"
        options={timezones}
        selected={timezone}
        onSelect={(tz) => {
          setTimezoneDraft(tz);
          setTimezonePickerOpen(false);
        }}
        onClose={() => setTimezonePickerOpen(false)}
      />
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  retakeTourRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth,
  },
  retakeTourText: { flex: 1, marginRight: spacing.sm },
  picker: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, minHeight: 48,
  },
  pickerText: { fontSize: 15, flex: 1, marginRight: spacing.sm },
  chevron: { fontSize: 20, lineHeight: 20 },
  segments: { flexDirection: 'row', borderWidth: 1, borderRadius: radius.md, overflow: 'hidden' },
  segment: { flex: 1, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  segmentText: { fontSize: 13, fontWeight: '600' },
  hint: { fontSize: 11, lineHeight: 16, marginTop: spacing.sm },
  error: { fontSize: 13, marginTop: spacing.sm },
  saveRow: { alignItems: 'flex-end', marginVertical: spacing.sm, minHeight: 16 },
});
