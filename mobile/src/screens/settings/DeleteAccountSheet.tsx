import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Button } from '../../components/Button';
import { TextField } from '../../components/TextField';
import { accountLifecycleApi, passwordChangeApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode, type PhoneConfirmation } from '../../lib/phoneAuth';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { sanitizeOtp } from '../../lib/validation';
import { radius, spacing, useTheme } from '../../theme';
import { AccountActionSheet, sheetStyles } from './AccountActionSheet';

type Step = 'password' | 'otp' | 'confirm';

/**
 * Permanent, irreversible: current-password + phone OTP gated, forked from
 * ChangePasswordSheet.tsx's identical password -> otp steps (same session-backed
 * passwordChangeApi.start/verifyOtp calls) -- and from
 * frontend/src/components/DeleteAccountModal.tsx, whose exact re-auth bar this mirrors. Diverges
 * after verifyOtp: no new password, just a danger-styled confirm step calling
 * accountLifecycleApi.deleteAccount(sessionId), which consumes the session server-side
 * (PasswordChangeService.consumeForAccountDeletion) rather than completing a password change.
 *
 * Cancel/backdrop-dismiss stays available through the confirm step too (matching web, which keeps
 * its own Cancel button right next to Permanently Delete Account) -- what's deliberately absent,
 * per UserAccountLifecycleService.requestDeletion's own doc comment, is any grace-period AFTER
 * submission: deletion is instant, not a scheduled/undoable delete, so `dismissable` only turns
 * off once submitDelete() is actually in flight, not for the whole confirm step.
 *
 * Bug fix (review): the confirm step's only recovery from a failed submitDelete() (e.g. a session
 * that expired while the user was reading the warning) used to be the generic Cancel button --
 * functionally equivalent to startOver() but not obviously so, unlike the OTP step's own explicit
 * "Didn't get a code? Start over" link. Confirm now offers the same explicit startOver() link,
 * always visible (not just after an error) for the same reason the OTP step's is: consistency,
 * and one less thing to figure out under an already-anxious "delete my account" flow.
 *
 * Google/Apple accounts: see DeactivateAccountSheet's identical doc comment for why this shows a
 * "not yet available" message with a support link instead of a broken re-auth step.
 *
 * onDeleted is responsible for the actual sign-out (AuthContext.logout()) -- requestDeletion
 * already revoked every refresh token server-side, so there is nothing left to be signed in to.
 */
export function DeleteAccountSheet({ onClose, onDeleted, signInMethod, onContactSupport }: {
  onClose: () => void;
  onDeleted: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
  onContactSupport: () => void;
}) {
  const c = useTheme();
  const singleFlight = useSingleFlight();

  const [step, setStep] = useState<Step>('password');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [maskedPhone, setMaskedPhone] = useState('');
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [otp, setOtp] = useState('');
  const [understood, setUnderstood] = useState(false);

  const otpValid = /^\d{6}$/.test(otp);

  async function submitCurrentPassword() {
    if (currentPassword.length === 0) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        const res = await passwordChangeApi.start(currentPassword, null, null);
        setSessionId(res.sessionId);
        setMaskedPhone(res.maskedPhone);
        setConfirmation(await sendPhoneVerificationCode(res.phoneNumber));
        setStep('otp');
      } catch (e) {
        setError(toUserMessage(e, 'Could not start account deletion. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  async function submitOtp() {
    if (!sessionId || !confirmation || !otpValid) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        const idToken = await confirmPhoneVerificationCode(confirmation, otp);
        await passwordChangeApi.verifyOtp(sessionId, idToken);
        setStep('confirm');
      } catch (e) {
        setError(toUserMessage(e, 'Could not verify that code. Please try again.'));
        setOtp('');
      } finally {
        setSubmitting(false);
      }
    });
  }

  function startOver() {
    setStep('password');
    setOtp('');
    setSessionId(null);
    setConfirmation(null);
    setUnderstood(false);
    setError(null);
  }

  async function submitDelete() {
    if (!sessionId || !understood) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        await accountLifecycleApi.deleteAccount(sessionId);
        onDeleted();
      } catch (e) {
        setError(toUserMessage(e, 'Could not delete your account. Please try again.'));
        setSubmitting(false);
      }
    });
  }

  // Only the confirm step is a real point of no return -- earlier steps just re-prove identity,
  // same as ChangePasswordSheet's identical dismissable rule.
  const dismissable = !submitting;

  return (
    <AccountActionSheet onClose={onClose} dismissable={dismissable} closeLabel="Close delete account">
      <Text style={[sheetStyles.title, { color: c.ink }]}>Delete Account</Text>

      {signInMethod !== 'PASSWORD' ? (
        <View style={[sheetStyles.notice, { backgroundColor: c.primaryLight }]}>
          <Text style={[sheetStyles.noticeText, { color: c.ink }]}>
            Deleting a {signInMethod === 'GOOGLE' ? 'Google' : 'Apple'}-linked account isn&apos;t
            available in the app yet. Contact support and we&apos;ll take care of it.
          </Text>
          <Button label="Contact Support" variant="link" onPress={onContactSupport} />
        </View>
      ) : (
        <>
          {step === 'password' ? (
            <>
              <Text style={[sheetStyles.body, { color: c.muted }]}>
                Deleting your account is permanent and cannot be undone. Enter your current
                password to get started — we&apos;ll send a verification code to the phone on file.
              </Text>
              <TextField
                label="Current password"
                value={currentPassword}
                onChangeText={(v) => { setCurrentPassword(v); setError(null); }}
                secure
                autoCapitalize="none"
                textContentType="password"
              />
              {error ? <Text style={[sheetStyles.error, { color: c.danger }]}>{error}</Text> : null}
              <View style={sheetStyles.action}>
                <Button
                  label={submitting ? 'Sending…' : 'Send code'}
                  onPress={() => void submitCurrentPassword()}
                  loading={submitting}
                  disabled={currentPassword.length === 0}
                />
              </View>
            </>
          ) : null}

          {step === 'otp' ? (
            <>
              <Text style={[sheetStyles.body, { color: c.muted }]}>
                Enter the 6-digit code sent to {maskedPhone}.
              </Text>
              <TextField
                label="Verification code"
                value={otp}
                onChangeText={(v) => { setOtp(sanitizeOtp(v)); setError(null); }}
                keyboardType="number-pad"
                textContentType="oneTimeCode"
                autoComplete="sms-otp"
                placeholder="123456"
              />
              {error ? <Text style={[sheetStyles.error, { color: c.danger }]}>{error}</Text> : null}
              <View style={sheetStyles.action}>
                <Button
                  label={submitting ? 'Verifying…' : 'Verify'}
                  onPress={() => void submitOtp()}
                  loading={submitting}
                  disabled={!otpValid}
                />
                {/* Bug fix (review): had no disabled={submitting} guard, unlike every other
                    action button in this file. submitOtp() (like submitCurrentPassword()) is
                    only single-flight-protected against a SECOND identical call, not against an
                    unrelated action firing mid-flight -- tapping this while verifyOtp() is still
                    in the air resets step/sessionId/confirmation via startOver(), and then the
                    stale call's own success path (setStep('confirm')) can land after the reset
                    and yank the UI forward again with state the user just abandoned. */}
                <Button label="Didn't get a code? Start over" variant="link" onPress={startOver} disabled={submitting} />
              </View>
            </>
          ) : null}

          {step === 'confirm' ? (
            <>
              <View style={[styles.warnBox, { backgroundColor: c.dangerBg, borderColor: c.danger }]}>
                <Text style={[styles.warnTitle, { color: c.danger }]}>This cannot be undone.</Text>
                <Text style={[styles.warnBody, { color: c.ink }]}>
                  Your account and all your data are permanently deleted immediately. You&apos;ll be
                  signed out everywhere right away — there is no way to cancel this request once
                  submitted.
                </Text>
              </View>

              <Pressable
                onPress={() => setUnderstood((v) => !v)}
                style={styles.confirmRow}
                accessibilityRole="checkbox"
                accessibilityState={{ checked: understood }}
                accessibilityLabel="I understand this permanently deletes my account and cannot be undone."
              >
                <View
                  style={[
                    styles.checkbox,
                    { borderColor: understood ? c.danger : c.border, backgroundColor: understood ? c.danger : 'transparent' },
                  ]}
                >
                  {understood ? <Text style={[styles.tick, { color: c.onPrimary }]}>✓</Text> : null}
                </View>
                <Text style={[styles.confirmLabel, { color: c.ink }]}>
                  I understand this permanently deletes my account and cannot be undone.
                </Text>
              </Pressable>

              {error ? <Text style={[sheetStyles.error, { color: c.danger }]}>{error}</Text> : null}
              <View style={sheetStyles.action}>
                <Button
                  label={submitting ? 'Deleting…' : 'Permanently Delete Account'}
                  onPress={() => void submitDelete()}
                  loading={submitting}
                  disabled={!understood}
                />
                {/* Same explicit escape hatch as the OTP step's own "Start over" -- the generic
                    Cancel button below closes the whole sheet, which is functionally equivalent
                    after a failed submission but easy to miss as the recovery action while
                    staring at an error on an already-tense screen. */}
                <Button label="Start over" variant="link" onPress={startOver} disabled={submitting} />
              </View>
            </>
          ) : null}

          <Button label="Cancel" variant="link" onPress={onClose} disabled={!dismissable} />
        </>
      )}
    </AccountActionSheet>
  );
}

const styles = StyleSheet.create({
  warnBox: { borderWidth: 1, borderRadius: radius.md, padding: 12, marginBottom: spacing.sm, gap: 6 },
  warnTitle: { fontSize: 13, fontWeight: '700' },
  warnBody: { fontSize: 12, lineHeight: 17 },
  confirmRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, marginBottom: spacing.sm },
  checkbox: {
    width: 22, height: 22, borderRadius: 5, borderWidth: 2,
    alignItems: 'center', justifyContent: 'center', marginTop: 1,
  },
  tick: { fontSize: 14, fontWeight: '700' },
  confirmLabel: { fontSize: 12, lineHeight: 17, flex: 1 },
});
