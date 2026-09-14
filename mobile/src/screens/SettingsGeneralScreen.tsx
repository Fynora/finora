import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { SaveStatus } from '../components/AccountUI';
import { Button } from '../components/Button';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TextField } from '../components/TextField';
import { userApi, onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, THEME_SETTINGS, useTheme, useThemeSetting, type ThemeSetting } from '../theme';

const THEME_LABEL: Record<ThemeSetting, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
};

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
  const { setting: themeSetting, setSetting: setThemeSetting } = useThemeSetting();
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
      setPrefsError('Low balance alert must be a number greater than zero.');
      return;
    }
    setPrefsError(null);
    await singleFlight(async () => {
      setPrefsSaving(true);
      try {
        const updated = await userApi.update({ lowBalanceThreshold: amount, timezone });
        queryClient.setQueryData(['user-settings'], updated);
        setLowBalanceDraft(null);
        setTimezoneDraft(null);
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
        confirmPrefsSaved();
      } catch (e) {
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
      try {
        await onboardingApi.reset();
        setOnboardingCompleted(false);
      } catch (e) {
        setRetakeTourError(toUserMessage(e, 'Could not restart the tour.'));
      } finally {
        setRetakingTour(false);
      }
    });
  }

  if (userQ.isLoading) {
    return <View style={[styles.content, { backgroundColor: c.bg }]} />;
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
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
              onPress={() => setThemeSetting(option)}
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

      {prefsError ? <Text style={[styles.error, { color: c.danger }]}>{prefsError}</Text> : null}
      <View style={styles.saveRow}>
        <SaveStatus dirty={prefsDirty} saving={prefsSaving} justSaved={prefsJustSaved} error={false} />
      </View>
      <Button
        label={prefsSaving ? 'Saving…' : 'Save preferences'}
        onPress={() => void savePreferences()}
        loading={prefsSaving}
        disabled={!prefsDirty}
      />

      {retakeTourError ? <Text style={[styles.error, { color: c.danger }]}>{retakeTourError}</Text> : null}
      <View style={[styles.retakeTourRow, { borderTopColor: c.border }]}>
        <View style={styles.retakeTourText}>
          <Text style={[styles.fieldLabel, { color: c.ink, marginTop: 0 }]}>Retake Product Tour</Text>
          <Text style={[styles.hint, { color: c.mutedInk }]}>Replay the onboarding experience anytime.</Text>
        </View>
        <Button label="Retake Tour" onPress={() => void retakeTour()} loading={retakingTour} variant="link" />
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
  );
}

const styles = StyleSheet.create({
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
