import { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import type { UserSettings } from '../api/endpoints';
import { TERMS_VERSION_OUTDATED } from '../api/errorCodes';
import { Button } from '../components/Button';
import { ConsentChoices } from '../components/consent/ConsentChoices';
import { ConsentNotice } from '../components/consent/ConsentNotice';
import { GlassScreen } from '../components/GlassScreen';
import { DeleteAccountSheet } from '../screens/settings/DeleteAccountSheet';
import { useTheme } from '../theme';

interface Props {
  profile: UserSettings;
  onSubmit: (body: { acceptTermsVersion?: string; aiProcessing: boolean }) => Promise<void>;
  /** The documents changed while this was open: reload the profile (and so the version). */
  onOutdated: () => Promise<void>;
  onSignOut: () => void;
}

/**
 * The full-screen consent step (V302), shown by RootNavigator while useConsent says it is needed:
 * the notice, the required box when this account has not accepted the current Terms version, and
 * the AI box. Nothing else is reachable until it is answered. Ways out: read the documents (links
 * open the website), sign out, or delete the account. Same flow as the web app's ConsentStep.
 */
export function ConsentScreen({ profile, onSubmit, onOutdated, onSignOut }: Props) {
  const c = useTheme();
  const needsTerms = profile.termsAcceptanceRequired;
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [aiEnabled, setAiEnabled] = useState(profile.aiProcessingConsent === true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const canContinue = !needsTerms || termsAccepted;

  async function submit() {
    if (!canContinue || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit({
        ...(needsTerms ? { acceptTermsVersion: profile.currentTermsVersion } : {}),
        aiProcessing: aiEnabled,
      });
    } catch (err: any) {
      if (err?.response?.data?.errorCode === TERMS_VERSION_OUTDATED) {
        setTermsAccepted(false);
        setError('The Terms of Service or Privacy Policy changed just now. Please read them again before you agree.');
        await onOutdated().catch(() => {});
      } else {
        setError('Something went wrong. Check your connection and try again.');
      }
      setSaving(false);
    }
  }

  return (
    <GlassScreen style={styles.glassRoot}>
      <ScrollView contentContainerStyle={styles.container}>
        <Text style={[styles.title, { color: c.ink }]}>
          {needsTerms ? 'Before you continue' : 'One choice about AI features'}
        </Text>
        <Text style={[styles.subtitle, { color: c.muted }]}>
          {needsTerms
            ? 'Please read how Fynora uses your data, then confirm below.'
            : 'Fynora now asks before using AI features. They stay off unless you tick the box.'}
        </Text>
        <View style={[styles.notice, { borderColor: c.border }]}>
          <ConsentNotice />
        </View>
        <ConsentChoices
          showTermsBox={needsTerms}
          termsAccepted={termsAccepted}
          onTermsAcceptedChange={setTermsAccepted}
          aiEnabled={aiEnabled}
          onAiEnabledChange={setAiEnabled}
        />
        {error ? <Text accessibilityRole="alert" style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
        <View style={{ height: 16 }} />
        <Button label="Continue" onPress={() => void submit()} disabled={!canContinue} loading={saving} />
        <View style={{ height: 8 }} />
        <Button label="Sign out" variant="link" onPress={onSignOut} />
        <Button label="Delete my account" variant="link" onPress={() => setDeleteOpen(true)} />
      </ScrollView>
      {deleteOpen ? (
        <DeleteAccountSheet
          onClose={() => setDeleteOpen(false)}
          onDeleted={() => { setDeleteOpen(false); onSignOut(); }}
          signInMethod={profile.signInMethod}
        />
      ) : null}
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  container: { flexGrow: 1, alignItems: 'stretch', justifyContent: 'center', padding: 24 },
  title: { fontSize: 22, fontWeight: '700', marginBottom: 8, textAlign: 'center' },
  subtitle: { fontSize: 13, textAlign: 'center', marginBottom: 16 },
  notice: { borderWidth: 1, borderRadius: 10, padding: 12, marginBottom: 12 },
  error: { fontSize: 13, textAlign: 'center', marginTop: 8 },
});
