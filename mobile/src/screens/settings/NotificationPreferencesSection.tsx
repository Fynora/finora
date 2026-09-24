import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, Switch, Text, View } from 'react-native';
import { notificationPreferencesApi, type NotificationPreference } from '../../api/endpoints';
import { spacing, useTheme } from '../../theme';

const CHANNEL_COPY: Record<NotificationPreference['channel'], { label: string; description: string }> = {
  EMAIL: {
    label: 'Email',
    description: 'Statement and referral updates by email: a statement is ready, being checked or resolved, and referral rewards.',
  },
  PUSH: {
    label: 'Push notifications',
    description: 'The same updates as notifications on every phone you use Fynora on.',
  },
};

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

  useEffect(() => {
    let cancelled = false;
    notificationPreferencesApi.list()
      .then((p) => { if (!cancelled) setPrefs(p); })
      .catch(() => { if (!cancelled) setLoadError(true); });
    return () => { cancelled = true; };
  }, []);

  async function toggle(pref: NotificationPreference, next: boolean) {
    setSaving(true);
    setSaveError(null);
    try {
      setPrefs(await notificationPreferencesApi.set(pref.channel, next));
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
        <Text style={[styles.meta, { color: c.danger }]}>
          Couldn&apos;t load your notification settings. Try reopening Settings.
        </Text>
      ) : prefs === null ? (
        <ActivityIndicator color={c.primary} accessibilityLabel="Loading your notification settings" />
      ) : (
        <>
          {/* A channel this build has no copy for (a newer backend) is skipped, not rendered blank --
              matters more here than on web, since an installed app can lag the backend for months. */}
          {prefs.filter((pref) => pref.channel in CHANNEL_COPY).map((pref) => {
            const copy = CHANNEL_COPY[pref.channel];
            return (
              <View key={pref.channel} style={[styles.row, { borderBottomColor: c.border }]}>
                <View style={styles.rowMain}>
                  <Text style={[styles.rowTitle, { color: c.ink }]}>{copy.label}</Text>
                  <Text style={[styles.meta, { color: c.mutedInk }]}>{copy.description}</Text>
                </View>
                <Switch
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
          {saveError ? <Text style={[styles.meta, { color: c.danger }]}>{saveError}</Text> : null}
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
