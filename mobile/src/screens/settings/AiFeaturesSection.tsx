import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { AppAlert } from '../../lib/appAlert';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { userApi } from '../../api/endpoints';
import { AppSwitch } from '../../components/AppSwitch';
import { spacing, useTheme } from '../../theme';

/**
 * Settings -> Data -> AI features (privacy workstream 7): the one switch for Ask Fyn, AI insights
 * and AI category suggestions. Turning it off is withdrawal: the backend stops every AI call and
 * deletes the AI insight texts written so far, so the switch asks first. Same as the web app's
 * AiFeaturesSection. Reads the screen's own ['user-settings'] query and writes the answer back
 * into it.
 */
export function AiFeaturesSection() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const userQ = useQuery({ queryKey: ['user-settings'], queryFn: () => userApi.get() });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const enabled = userQ.data?.aiProcessingConsent === true;

  async function save(next: boolean) {
    setSaving(true);
    setError(null);
    try {
      const updated = await userApi.consent({ aiProcessing: next });
      queryClient.setQueryData(['user-settings'], updated);
    } catch {
      setError("Couldn't save that just now — please try again.");
    } finally {
      setSaving(false);
    }
  }

  function onValueChange(next: boolean) {
    if (next) {
      void save(true);
      return;
    }
    AppAlert.alert(
      'Turn off AI features?',
      'Fynora stops sending anything to the AI service. The AI insight texts written for you so far are deleted, and the details of past AI requests are cleared. Your Ask Fyn conversations stay. You can turn AI features back on at any time.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Turn off', style: 'destructive', onPress: () => void save(false) },
      ],
    );
  }

  return (
    <View style={[styles.row, { borderTopColor: c.border }]} testID="ai-features">
      <View style={styles.text}>
        <Text style={[styles.label, { color: c.ink }]}>AI features</Text>
        <Text style={[styles.hint, { color: c.mutedInk }]}>
          Ask Fyn, AI insights and AI category suggestions. When on, the question you ask, the account figures
          needed to answer it, or the description of a transaction you add by hand are sent to Anthropic
          (Claude), with names and account numbers Fynora recognises hidden first.
        </Text>
        {userQ.isError ? (
          <Text style={[styles.hint, { color: c.warningInk }]}>Couldn&apos;t load this setting just now.</Text>
        ) : null}
        {error ? <Text accessibilityRole="alert" style={[styles.hint, { color: c.dangerInk }]}>{error}</Text> : null}
      </View>
      <AppSwitch
        value={enabled}
        onValueChange={onValueChange}
        disabled={!userQ.data || saving}
        trackColor={{ true: c.primary, false: c.border }}
        // Same reason as AppLockSection: a white thumb vanishes on dark mode's light track.
        thumbColor={enabled ? c.onPrimary : undefined}
        accessibilityLabel="AI features"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth,
  },
  text: { flex: 1, marginRight: spacing.sm },
  label: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  hint: { fontSize: 11, lineHeight: 16, marginTop: spacing.sm },
});
