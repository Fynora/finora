import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { AppSwitch } from '../../components/AppSwitch';
import { LoadFailed } from '../../components/LoadFailed';
import { NotificationPreferencesRowsSkeleton } from '../../components/skeletons/ScreenSkeletons';
import { SkeletonGroup } from '../../components/skeletons/Skeletons';
import { notificationPreferencesApi, type NotificationPreference } from '../../api/endpoints';
import { spacing, useTheme } from '../../theme';

type PrefKey = `${NotificationPreference['category']}:${NotificationPreference['channel']}`;

const PREF_COPY: Partial<Record<PrefKey, { label: string; description: string }>> = {
  'FINANCIAL:EMAIL': {
    label: 'Email',
    description: 'Statement and referral updates by email: a statement is ready, being checked or resolved, and referral rewards.',
  },
  'FINANCIAL:PUSH': {
    label: 'Push notifications',
    description: 'The same updates as notifications on every phone you use Fynora on.',
  },
  // Gate 2b (spec §6, Sid's decision (b)): reminders have their own switch, so turning them off
  // never silences "your statement is ready".
  'REMINDERS:PUSH': {
    label: 'Statement reminders',
    description: 'A push at the start of the month when a new statement has probably arrived.',
  },
  // V305: off until the user turns it on. Promotional campaigns reach only people who did.
  'MARKETING:PUSH': {
    label: 'Offers and news',
    description: 'Occasional push notifications about offers, discounts and new features. Off unless you turn it on.',
  },
};

const keyOf = (pref: NotificationPreference): PrefKey => `${pref.category}:${pref.channel}`;

/**
 * Mobile counterpart to web's settings/NotificationsPane -- the switch a FINANCIAL email's "Turn
 * them off in your notification settings" line is about. Self-contained like AppLockSection: its
 * own fetch, loading and error state, dropped into SettingsGeneralScreen. Security messages are not
 * listed because the backend always sends them.
 */
export function NotificationPreferencesSection() {
  const c = useTheme();
  const [prefs, setPrefs] = useState<NotificationPreference[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Bumped by "Try again" to run the load below once more.
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    notificationPreferencesApi.list()
      .then((p) => { if (!cancelled) setPrefs(p); })
      .catch(() => { if (!cancelled) setLoadError(true); });
    return () => { cancelled = true; };
  }, [attempt]);

  function retryLoad() {
    setLoadError(false);
    setPrefs(null);
    setAttempt((a) => a + 1);
  }

  async function toggle(pref: NotificationPreference, next: boolean) {
    setSaving(true);
    setSaveError(null);
    try {
      setPrefs(await notificationPreferencesApi.set(pref.category, pref.channel, next));
    } catch {
      setSaveError("Couldn't save that change. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <View style={[styles.section, { borderTopColor: c.border }]}>
      <Text style={[styles.heading, { color: c.ink }]}>Notifications</Text>
      {loadError ? (
        <LoadFailed message="Couldn't load your notification settings." tone="danger" onRetry={retryLoad} />
      ) : prefs === null ? (
        <SkeletonGroup>
          <NotificationPreferencesRowsSkeleton />
        </SkeletonGroup>
      ) : (
        <>
          {/* A preference this build has no copy for (a newer backend) is skipped, not rendered blank --
              matters more here than on web, since an installed app can lag the backend for months. */}
          {prefs.filter((pref) => PREF_COPY[keyOf(pref)]).map((pref) => {
            const copy = PREF_COPY[keyOf(pref)]!;
            return (
              <View key={keyOf(pref)} style={[styles.row, { borderBottomColor: c.border }]}>
                <View style={styles.rowMain}>
                  <Text style={[styles.rowTitle, { color: c.ink }]}>{copy.label}</Text>
                  <Text style={[styles.meta, { color: c.mutedInk }]}>{copy.description}</Text>
                </View>
                <AppSwitch
                  value={pref.enabled}
                  onValueChange={(next) => void toggle(pref, next)}
                  disabled={saving}
                  trackColor={{ true: c.primary, false: c.border }}
                  // Same reason as AppLockSection: a white thumb vanishes on dark mode's light track.
                  thumbColor={pref.enabled ? c.onPrimary : undefined}
                  accessibilityLabel={copy.label}
                />
              </View>
            );
          })}
          {saveError ? <Text style={[styles.meta, { color: c.dangerInk }]}>{saveError}</Text> : null}
          <Text style={[styles.meta, { color: c.mutedInk, marginTop: spacing.sm }]}>
            Security messages — sign-in codes, password changes and account changes — are always sent.
          </Text>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { borderTopWidth: StyleSheet.hairlineWidth, marginTop: spacing.lg, paddingTop: spacing.md },
  heading: { fontSize: 14, fontWeight: '700', marginBottom: spacing.xs },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  meta: { fontSize: 12, marginTop: 2 },
});
