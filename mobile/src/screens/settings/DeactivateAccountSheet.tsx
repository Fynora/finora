import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { AppleReauthPrompt } from '../../components/AppleReauthPrompt';
import { Button } from '../../components/Button';
import { GoogleReauthPrompt } from '../../components/GoogleReauthPrompt';
import { OptionPickerModal } from '../../components/OptionPickerModal';
import { TextField } from '../../components/TextField';
import { accountLifecycleApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../../lib/monitoring';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';
import { AccountActionSheet, sheetStyles } from './AccountActionSheet';

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
 * Google/Apple accounts have no password anyone knows, so they prove who they are with a fresh
 * Google or Apple credential instead (GoogleReauthPrompt / AppleReauthPrompt, the same step-up
 * ChangePasswordSheet uses). The credential is the last step: the reason is chosen first, and
 * verifying with Google/Apple then deactivates straight away, so a fresh token is never held
 * waiting for the rest of the form.
 *
 * onDeactivated is responsible for the actual sign-out (AuthContext.logout()) -- there is nothing
 * left to be signed in to once this succeeds, since UserAccountLifecycleService.deactivate already
 * revoked every refresh token server-side.
 */
export function DeactivateAccountSheet({ onClose, onDeactivated, signInMethod }: {
  onClose: () => void;
  onDeactivated: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
}) {
  const c = useTheme();
  const singleFlight = useSingleFlight();

  const [currentPassword, setCurrentPassword] = useState('');
  const [reason, setReason] = useState<{ value: string; label: string } | null>(null);
  const [reasonPickerOpen, setReasonPickerOpen] = useState(false);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSubmitPassword = signInMethod === 'PASSWORD' && currentPassword.length > 0 && reason !== null;

  /** Exactly one of the three is non-null, matching the backend's request contract. */
  async function submitWithCredential(
    currentPasswordArg: string | null, googleIdToken: string | null, appleIdToken: string | null
  ) {
    if (!reason) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      const startedAt = requestStartedAt();
      try {
        await accountLifecycleApi.deactivate(
          currentPasswordArg, googleIdToken, appleIdToken, reason.value, note.trim() || undefined);
        onDeactivated();
      } catch (e) {
        reportTransportFailure(e, 'deactivate-account:submit', startedAt);
        setError(toUserMessage(e, 'Could not deactivate your account. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  return (
    <AccountActionSheet onClose={onClose} dismissable={!submitting} closeLabel="Close deactivate account">
      <Text style={[sheetStyles.title, { color: c.ink }]}>Deactivate Account</Text>
      <Text style={[sheetStyles.body, { color: c.muted }]}>
        You&apos;ll be signed out everywhere and won&apos;t be able to sign back in until you
        reactivate. Your data is retained securely — nothing is deleted, and you can reactivate any
        time just by signing in again.
      </Text>

      {signInMethod === 'PASSWORD' ? (
        <TextField
          label="Current password"
          value={currentPassword}
          onChangeText={(v) => { setCurrentPassword(v); setError(null); }}
          secure
          autoCapitalize="none"
          textContentType="password"
        />
      ) : null}

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

      {signInMethod === 'PASSWORD' ? (
        <>
          {error ? <Text style={[sheetStyles.error, { color: c.dangerInk }]}>{error}</Text> : null}
          <View style={sheetStyles.action}>
            <Button
              label={submitting ? 'Deactivating…' : 'Deactivate Account'}
              onPress={() => void submitWithCredential(currentPassword, null, null)}
              loading={submitting}
              disabled={!canSubmitPassword}
            />
          </View>
        </>
      ) : (
        <>
          {/* The prompt stays mounted while the call runs: the button awaits onCredential and
              shows its own locked, dimmed state until it settles. */}
          {reason === null ? (
            <Text style={[sheetStyles.body, { color: c.muted }]}>
              Choose a reason, then verify with {signInMethod === 'GOOGLE' ? 'Google' : 'Apple'} to
              deactivate.
            </Text>
          ) : signInMethod === 'GOOGLE' ? (
            <GoogleReauthPrompt
              onCredential={(idToken) => submitWithCredential(null, idToken, null)}
              onError={setError}
            />
          ) : (
            <AppleReauthPrompt
              onCredential={(idToken) => submitWithCredential(null, null, idToken)}
              onError={setError}
            />
          )}
          {submitting ? <Text style={[sheetStyles.body, { color: c.muted }]}>Deactivating…</Text> : null}
          {error ? <Text style={[sheetStyles.error, { color: c.dangerInk }]}>{error}</Text> : null}
        </>
      )}

      <Button label="Cancel" variant="link" onPress={onClose} disabled={submitting} />

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
    </AccountActionSheet>
  );
}

const styles = StyleSheet.create({
  picker: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.sm,
  },
  pickerLabel: { fontSize: 11, textTransform: 'uppercase', marginBottom: 4 },
  pickerValue: { fontSize: 14 },
});
