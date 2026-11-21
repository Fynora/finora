import { useState } from 'react';
import {
  KeyboardAvoidingView, Pressable, ScrollView, StyleSheet, Text, View
} from 'react-native';
import { AppModal } from '../../components/AppModal';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppleReauthPrompt } from '../../components/AppleReauthPrompt';
import { Button } from '../../components/Button';
import { GoogleReauthPrompt } from '../../components/GoogleReauthPrompt';
import { TextField } from '../../components/TextField';
import { passwordChangeApi } from '../../api/endpoints';
import { hapticError, hapticSuccess } from '../../lib/haptics';
import { safeStorage } from '../../lib/safeStorage';
import { toUserMessage } from '../../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../../lib/monitoring';
import {
  confirmPhoneVerificationCode, sendPhoneVerificationCode, type PhoneConfirmation,
} from '../../lib/phoneAuth';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { PASSWORD_HINT, sanitizeOtp } from '../../lib/validation';
import { radius, spacing, useTheme } from '../../theme';
import { GlassSurface } from '../../components/GlassSurface';

/**
 * The authenticated, OTP-gated Change Password flow, ported from
 * frontend/src/components/ChangePasswordModal.tsx.
 *
 * Current password -> code sent to the phone on file -> new password. Each step is validated
 * server-side against a persisted session (backend PasswordChangeService) rather than trusted from
 * whatever step this component thinks it is on.
 *
 * Deliberately a separate journey from Forgot Password: that one assumes the user CANNOT log in
 * and proves identity from scratch; this assumes they are logged in and know the current password.
 *
 * Two things the web version needs that this does not: a reCAPTCHA anchor element, and the
 * resetPhoneVerification() lifecycle around it. @react-native-firebase/auth verifies the app
 * natively, so the entire class of auth/argument-error retry bugs documented in that file cannot
 * occur here (see src/lib/phoneAuth.ts).
 *
 * The device completing this flow is never signed out -- every OTHER session always is (it was a
 * choice on the last step until audit F-04) -- so success simply closes the sheet.
 *
 * Phase 4 (Medium-Tier Parity): the first step is "prove who you are", not "type your current
 * password" specifically -- a GOOGLE/APPLE-method account has no password to type (see
 * User.signInMethod's own doc comment), so that step renders GoogleReauthPrompt/AppleReauthPrompt
 * instead and starts the SAME session with a fresh ID token in place of currentPassword
 * (PasswordChangeService.start already accepted all three; this sheet previously only ever sent
 * the one, same gap ChangeEmailSheet's own doc comment documented). Everything from the OTP step
 * onward is identical regardless of how identity was proven.
 */
type Step = 'password' | 'otp' | 'newPassword' | 'success';

// There was a strength bar here that suggested adding a capital, a number and a special
// character. It is gone (audit F-08, 2026-11-21): it rated "Password1!" as strong, which the
// server refuses. PASSWORD_HINT says what the server's rule is instead.

export function ChangePasswordSheet({ onClose, onSuccess, signInMethod }: {
  onClose: () => void;
  onSuccess?: () => void;
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [step, setStep] = useState<Step>('password');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [maskedPhone, setMaskedPhone] = useState('');
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [otp, setOtp] = useState('');

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  const confirmMismatch = confirmPassword.length > 0 && confirmPassword !== newPassword;
  const otpValid = /^\d{6}$/.test(otp);
  const canSubmitNewPassword = newPassword.length >= 8 && newPassword === confirmPassword;

  /**
   * Starts the session with whichever proof of identity this account actually has -- a password,
   * or a fresh Google/Apple credential. Exactly one of the three is ever non-null, matching
   * PasswordChangeService.start's own contract.
   */
  async function startWithCredential(
    currentPasswordArg: string | null, googleIdToken: string | null, appleIdToken: string | null
  ) {
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      const startedAt = requestStartedAt();
      try {
        const res = await passwordChangeApi.start(currentPasswordArg, googleIdToken, appleIdToken);
        setSessionId(res.sessionId);
        setMaskedPhone(res.maskedPhone);
        // Firebase sends the code itself, straight to the number this response reveals -- the
        // backend never does (see PasswordChangeService.start's own doc comment).
        setConfirmation(await sendPhoneVerificationCode(res.phoneNumber));
        setStep('otp');
      } catch (e) {
        hapticError();
        reportTransportFailure(e, 'change-password:start', startedAt);
        setError(toUserMessage(e, signInMethod === 'PASSWORD'
          ? 'Could not start the password change. Please try again.'
          : `We couldn't verify your ${signInMethod === 'GOOGLE' ? 'Google' : 'Apple'} account. Please try again.`
        ));
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
      const startedAt = requestStartedAt();
      try {
        const idToken = await confirmPhoneVerificationCode(confirmation, otp);
        await passwordChangeApi.verifyOtp(sessionId, idToken);
        setStep('newPassword');
      } catch (e) {
        // Covers Firebase (confirm) and the backend (verifyOtp) alike -- toUserMessage maps both.
        hapticError();
        reportTransportFailure(e, 'change-password:verify-otp', startedAt);
        setError(toUserMessage(e, 'Could not verify that code. Please try again.'));
        setOtp('');
      } finally {
        setSubmitting(false);
      }
    });
  }

  /** Starting over re-verifies the current password and issues a fresh code. There is no separate
   *  resend endpoint: a new code always requires re-proving current-password ownership first. */
  function startOver() {
    setStep('password');
    setOtp('');
    setSessionId(null);
    setConfirmation(null);
    setError(null);
  }

  async function submitNewPassword() {
    if (!sessionId || !canSubmitNewPassword) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      const startedAt = requestStartedAt();
      try {
        // Sent so the backend knows which session to SPARE when signing out the others -- this
        // device stays signed in either way (see CompleteRequest.currentRefreshToken).
        const currentRefreshToken = await safeStorage.getItem('finora_refresh_token');
        if (!currentRefreshToken) {
          setError('Your session information is missing. Please sign in again and retry.');
          return;
        }
        const res = await passwordChangeApi.complete(sessionId, newPassword, currentRefreshToken);
        hapticSuccess();
        setSuccessMessage(res.message);
        setStep('success');
        onSuccess?.();
      } catch (e) {
        hapticError();
        reportTransportFailure(e, 'change-password:complete', startedAt);
        setError(toUserMessage(e, 'Could not update your password. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  // Closing mid-request would leave the user unsure whether the change went through; on the
  // success step the sheet has its own Done button and the backdrop is not a way out.
  const dismissable = !submitting && step !== 'success';

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={dismissable ? onClose : () => {}}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable
          style={styles.backdrop}
          onPress={dismissable ? onClose : undefined}
          disabled={!dismissable}
          accessibilityLabel="Close change password"
        />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            {step === 'success' ? (
              <View style={styles.successBlock}>
                <Text style={[styles.successMark, { color: c.successInk }]}>✓</Text>
                <Text style={[styles.title, { color: c.ink }]}>Password updated</Text>
                <Text style={[styles.body, { color: c.muted }]}>{successMessage}</Text>
                <View style={styles.action}>
                  <Button label="Done" onPress={onClose} />
                </View>
              </View>
            ) : (
              <>
                <Text style={[styles.title, { color: c.ink }]}>Change Password</Text>

                {step === 'password' && signInMethod === 'PASSWORD' ? (
                  <>
                    <Text style={[styles.body, { color: c.muted }]}>
                      Enter your current password to get started. We&apos;ll send a verification code
                      to the phone on file.
                    </Text>
                    <TextField
                      label="Current password"
                      value={currentPassword}
                      onChangeText={setCurrentPassword}
                      secure
                      autoCapitalize="none"
                      textContentType="password"
                    />
                    {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
                    <View style={styles.action}>
                      <Button
                        label={submitting ? 'Sending…' : 'Send code'}
                        onPress={() => void startWithCredential(currentPassword, null, null)}
                        loading={submitting}
                        disabled={currentPassword.length === 0}
                      />
                    </View>
                  </>
                ) : null}

                {/* Phase 4. A GOOGLE/APPLE-method account has no password to type at all -- see
                    this component's own doc comment -- so the identity-proof step renders the
                    matching reauth prompt instead, and starts the same session with a fresh
                    credential in place of currentPassword. */}
                {step === 'password' && signInMethod !== 'PASSWORD' ? (
                  <>
                    {signInMethod === 'GOOGLE' ? (
                      <GoogleReauthPrompt
                        onCredential={(idToken) => startWithCredential(null, idToken, null)}
                        onError={setError}
                      />
                    ) : (
                      <AppleReauthPrompt
                        onCredential={(idToken) => startWithCredential(null, null, idToken)}
                        onError={setError}
                      />
                    )}
                    {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
                  </>
                ) : null}

                {step === 'otp' ? (
                  <>
                    <Text style={[styles.body, { color: c.muted }]}>
                      Enter the 6-digit code sent to {maskedPhone}.
                    </Text>
                    <TextField
                      label="Verification code"
                      value={otp}
                      // No maxLength: React Native truncates a pasted "Your code is 123456" before
                      // sanitizeOtp can pull the digits out. Same rule as RegisterScreen's phone
                      // field -- see sanitizeOtp's own comment.
                      onChangeText={(v) => { setOtp(sanitizeOtp(v)); setError(null); }}
                      keyboardType="number-pad"
                      textContentType="oneTimeCode"
                      autoComplete="sms-otp"
                      placeholder="123456"
                    />
                    {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
                    <View style={styles.action}>
                      <Button
                        label={submitting ? 'Verifying…' : 'Verify'}
                        onPress={() => void submitOtp()}
                        loading={submitting}
                        disabled={!otpValid}
                      />
                      <Button
                        label="Didn't get a code? Start over"
                        variant="link"
                        onPress={startOver}
                        disabled={submitting}
                      />
                    </View>
                  </>
                ) : null}

                {step === 'newPassword' ? (
                  <>
                    <TextField
                      label="New password"
                      value={newPassword}
                      onChangeText={setNewPassword}
                      secure
                      autoCapitalize="none"
                      textContentType="newPassword"
                    />
                    <Text style={[styles.passwordHint, { color: c.muted }]}>{PASSWORD_HINT}</Text>
                    <TextField
                      label="Confirm new password"
                      value={confirmPassword}
                      onChangeText={setConfirmPassword}
                      secure
                      autoCapitalize="none"
                      textContentType="newPassword"
                      error={confirmMismatch ? "Passwords don't match." : null}
                    />

                    <Text style={[styles.groupLabel, { color: c.muted }]}>Other devices</Text>
                    <Text style={[styles.otherDevicesNote, { color: c.ink, borderColor: c.border }]}>
                      Changing your password signs you out on every other device. This device stays signed in.
                    </Text>

                    {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
                    <View style={styles.action}>
                      <Button
                        label={submitting ? 'Updating…' : 'Update Password'}
                        onPress={() => void submitNewPassword()}
                        loading={submitting}
                        disabled={!canSubmitNewPassword}
                      />
                    </View>
                  </>
                ) : null}

                <Button label="Cancel" variant="link" onPress={onClose} disabled={!dismissable} />
              </>
            )}
          </ScrollView>
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
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
  passwordHint: { fontSize: 12, lineHeight: 17, marginBottom: spacing.sm },
  groupLabel: { fontSize: 12, fontWeight: '500', marginTop: spacing.sm, marginBottom: 6 },
  otherDevicesNote: {
    fontSize: 12,
    lineHeight: 17,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.xs,
  },
  successBlock: { alignItems: 'center', paddingVertical: spacing.lg },
  successMark: { fontSize: 34, fontWeight: '700', marginBottom: spacing.sm },
});
