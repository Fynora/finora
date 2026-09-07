import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { OptionPickerModal } from '../../components/OptionPickerModal';
import { TextField } from '../../components/TextField';
import { accountLifecycleApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';

// Mirrors User.DEACTIVATION_REASONS on the backend -- the DB CHECK constraint (V88) is the actual
// source of truth for the allowed set, same as frontend/src/components/DeactivateAccountModal.tsx's
// identical list; this is a display-label mapping, not a second copy of the validation rule.
const REASONS: { value: string; label: string }[] = [
  { value: 'TAKING_A_BREAK', label: 'Taking a break' },
  { value: 'NOT_USING_ANYMORE', label: "I'm not using it anymore" },
  { value: 'PRIVACY_CONCERNS', label: 'Privacy concerns' },
  { value: 'USING_ANOTHER_APP', label: "I'm using another app" },
  { value: 'OTHER', label: 'Other' },
];

/**
 * Reversible: current password only (no OTP), ported from
 * frontend/src/components/DeactivateAccountModal.tsx -- the caller already holds a valid session
 * and can undo this by simply signing in again (ReactivateAccountPrompt is the mobile Login flow's
 * existing handling for that, unchanged by this file).
 *
 * Google/Apple accounts: web branches here into a GoogleReauthPrompt step-up. Mobile doesn't have
 * that component yet (see ChangeEmailSheet's own doc comment for the same gap on a different
 * screen) -- building it is scoped to a later phase, not duplicated a third time here. Rather than
 * show a button that reaches this screen and then dead-ends on a 400 asking for a password that
 * doesn't apply to this account, `signInMethod !== 'PASSWORD'` renders an honest "not yet
 * available in the app" message instead, with a way to reach support.
 *
 * onDeactivated is responsible for the actual sign-out (AuthContext.logout()) -- there is nothing
 * left to be signed in to once this succeeds, since UserAccountLifecycleService.deactivate already
 * revoked every refresh token server-side.
 */
export function DeactivateAccountSheet({ onClose, onDeactivated, signInMethod, onContactSupport }: {
  onClose: () => void;
  onDeactivated: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
  onContactSupport: () => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [currentPassword, setCurrentPassword] = useState('');
  const [reason, setReason] = useState<{ value: string; label: string } | null>(null);
  const [reasonPickerOpen, setReasonPickerOpen] = useState(false);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmit = signInMethod === 'PASSWORD' && currentPassword.length > 0 && reason !== null;

  async function submit() {
    if (!canSubmit || !reason) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        await accountLifecycleApi.deactivate(currentPassword, null, reason.value, note.trim() || undefined);
        onDeactivated();
      } catch (e) {
        setError(toUserMessage(e, 'Could not deactivate your account. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={submitting ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={submitting ? undefined : onClose}
          disabled={submitting}
          accessibilityLabel="Close deactivate account"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>Deactivate Account</Text>
            <Text style={[styles.body, { color: c.muted }]}>
              You&apos;ll be signed out everywhere and won&apos;t be able to sign back in until you
              reactivate. Your data is retained securely — nothing is deleted, and you can
              reactivate any time just by signing in again.
            </Text>

            {signInMethod !== 'PASSWORD' ? (
              <View style={[styles.notice, { backgroundColor: c.primaryLight }]}>
                <Text style={[styles.noticeText, { color: c.ink }]}>
                  Deactivating a {signInMethod === 'GOOGLE' ? 'Google' : 'Apple'}-linked account isn&apos;t
                  available in the app yet. Contact support and we&apos;ll take care of it.
                </Text>
                <Button label="Contact Support" variant="link" onPress={onContactSupport} />
              </View>
            ) : (
              <>
                <TextField
                  label="Current password"
                  value={currentPassword}
                  onChangeText={(v) => { setCurrentPassword(v); setError(null); }}
                  secure
                  autoCapitalize="none"
                  textContentType="password"
                />

                <Pressable
                  onPress={() => setReasonPickerOpen(true)}
                  style={[styles.picker, { borderColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel="Reason for deactivating"
                >
                  <Text style={[styles.pickerLabel, { color: c.muted }]}>Reason</Text>
                  <Text style={[styles.pickerValue, { color: reason ? c.ink : c.muted }]}>
                    {reason?.label ?? 'Choose a reason…'}
                  </Text>
                </Pressable>

                <TextField
                  label="Anything else? (optional)"
                  value={note}
                  onChangeText={(v) => setNote(v.slice(0, 500))}
                  multiline
                />

                {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
                <View style={styles.action}>
                  <Button
                    label={submitting ? 'Deactivating…' : 'Deactivate Account'}
                    onPress={() => void submit()}
                    loading={submitting}
                    disabled={!canSubmit}
                  />
                </View>
              </>
            )}

            <Button label="Cancel" variant="link" onPress={onClose} disabled={submitting} />
          </ScrollView>
        </View>
      </KeyboardAvoidingView>

      <OptionPickerModal
        visible={reasonPickerOpen}
        title="Reason"
        options={REASONS.map((r) => r.label)}
        selected={reason?.label ?? null}
        onSelect={(label) => {
          setReason(REASONS.find((r) => r.label === label) ?? null);
          setReasonPickerOpen(false);
        }}
        onClose={() => setReasonPickerOpen(false)}
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '88%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
  title: { fontSize: 17, fontWeight: '700', marginBottom: 4 },
  body: { fontSize: 13, lineHeight: 19, marginBottom: spacing.md },
  error: { fontSize: 13, marginBottom: spacing.sm },
  action: { marginTop: spacing.sm, gap: spacing.xs },
  notice: { borderRadius: radius.md, padding: 12, marginBottom: spacing.sm, gap: 4 },
  noticeText: { fontSize: 12, lineHeight: 17 },
  picker: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.sm,
  },
  pickerLabel: { fontSize: 11, textTransform: 'uppercase', marginBottom: 4 },
  pickerValue: { fontSize: 14 },
});
