import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { authApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { maskPhone } from '../lib/maskPhone';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import {
  confirmPhoneVerificationCode, sendPhoneVerificationCode, type PhoneConfirmation,
} from '../lib/phoneAuth';
import { useSingleFlight } from '../lib/useSingleFlight';
import { PHONE_PATTERN, passwordStrength, sanitizeOtp, sanitizePhoneNumber } from '../lib/validation';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'ResetPassword'>;

type Step = 'phone' | 'code' | 'password';

const SUCCESS_MESSAGE = 'Password reset successfully. Please sign in using your new password.';

/**
 * Finishes the emailed password-reset link inside the app -- the mobile counterpart of
 * frontend/src/pages/ResetPassword.tsx, with the same two proofs (BH-015): the link (proof of email
 * access) and an OTP to the account's own phone (proof of phone access).
 *
 * The user types their number rather than being shown it: verifyResetPasswordPhone confirms it
 * matches the account server-side and never returns the real one, so holding a reset link teaches
 * nobody the phone number. Only after that does Firebase send the SMS, to the number just typed.
 *
 * The Firebase ID token from the code step is kept in memory so a password the server rejects (same
 * as the current one, recently used) can be retried without a second SMS. The reset token comes from
 * route params and is never stored or logged.
 */
export function ResetPasswordScreen({ navigation, route }: Props) {
  const { token } = route.params;
  const c = useTheme();
  const singleFlight = useSingleFlight();

  const [step, setStep] = useState<Step>('phone');
  const [phone, setPhone] = useState('');
  const [phoneTouched, setPhoneTouched] = useState(false);
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [code, setCode] = useState('');
  const [idToken, setIdToken] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const phoneValid = PHONE_PATTERN.test(phone);
  const fullPhone = `+91${phone}`;
  const codeValid = /^\d{6}$/.test(code);
  const passwordLongEnough = password.length >= 8;
  const passwordsMatch = confirmPassword.length > 0 && confirmPassword === password;
  const strength = passwordStrength(password);

  async function submitPhone() {
    setPhoneTouched(true);
    if (!phoneValid) return;
    setError(null);
    await singleFlight(async () => {
      setBusy(true);
      const startedAt = requestStartedAt();
      try {
        await authApi.verifyResetPasswordPhone(token, fullPhone);
        setConfirmation(await sendPhoneVerificationCode(fullPhone));
        setNotice(null);
        setStep('code');
      } catch (e) {
        reportTransportFailure(e, 'reset-password:verify-phone', startedAt);
        setError(toUserMessage(e, 'Could not verify that number. Please try again.'));
      } finally {
        setBusy(false);
      }
    });
  }

  async function submitCode() {
    if (!confirmation || !codeValid) return;
    setError(null);
    await singleFlight(async () => {
      setBusy(true);
      const startedAt = requestStartedAt();
      try {
        setIdToken(await confirmPhoneVerificationCode(confirmation, code));
        setNotice(null);
        setStep('password');
      } catch (e) {
        reportTransportFailure(e, 'reset-password:verify-code', startedAt);
        setError(toUserMessage(e, 'Could not verify that code. Please try again.'));
        setCode('');
      } finally {
        setBusy(false);
      }
    });
  }

  async function resendCode() {
    setError(null);
    await singleFlight(async () => {
      setBusy(true);
      const startedAt = requestStartedAt();
      try {
        setConfirmation(await sendPhoneVerificationCode(fullPhone));
        setCode('');
        setNotice('We sent a new code.');
      } catch (e) {
        reportTransportFailure(e, 'reset-password:resend-code', startedAt);
        setError(toUserMessage(e, 'Could not send a new code. Please try again.'));
      } finally {
        setBusy(false);
      }
    });
  }

  function startOver() {
    setStep('phone');
    setConfirmation(null);
    setIdToken(null);
    setCode('');
    setPassword('');
    setConfirmPassword('');
    setError(null);
    setNotice(null);
  }

  async function submitPassword() {
    if (!idToken || !passwordLongEnough || !passwordsMatch) return;
    setError(null);
    await singleFlight(async () => {
      setBusy(true);
      const startedAt = requestStartedAt();
      try {
        await authApi.resetPassword(token, idToken, password);
        goToSignIn(SUCCESS_MESSAGE);
      } catch (e) {
        reportTransportFailure(e, 'reset-password:submit', startedAt);
        setError(toUserMessage(e, 'Could not reset your password. Please try again.'));
      } finally {
        setBusy(false);
      }
    });
  }

  // Rebuilds the auth stack as [AuthEntry, Login] instead of navigating: on a cold start this screen
  // sits directly above AuthEntry, so a plain navigate('Login') would leave it -- with the typed
  // password and a spent token -- one back press away.
  function goToSignIn(message?: string) {
    navigation.reset({
      index: 1,
      routes: [{ name: 'AuthEntry' }, message ? { name: 'Login', params: { message } } : { name: 'Login' }],
    });
  }

  const backToSignIn = (
    <Button label="Back to sign in" variant="link" onPress={() => goToSignIn()} disabled={busy} />
  );

  if (step === 'phone') {
    return (
      <AuthScreenLayout
        title="Confirm your phone number"
        subtitle="Enter the mobile number on your Fynora account. We'll text a verification code to it."
        error={error}
        footer={backToSignIn}
      >
        <TextField
          label="Mobile number"
          value={phone}
          onChangeText={(v) => { setPhone(sanitizePhoneNumber(v)); setError(null); }}
          onBlur={() => setPhoneTouched(true)}
          placeholder="XXXXXXXXXX"
          prefix="+91"
          keyboardType="number-pad"
          autoComplete="tel"
          // No maxLength: see RegisterScreen -- RN would truncate a pasted "+91…" before
          // sanitizePhoneNumber could strip the country code.
          error={phoneTouched && !phoneValid ? 'Enter a valid 10-digit mobile number (no leading 0-5).' : null}
        />
        <Button
          label={busy ? 'Checking…' : 'Send code'}
          onPress={() => void submitPhone()}
          loading={busy}
          disabled={!phoneValid}
        />
      </AuthScreenLayout>
    );
  }

  if (step === 'code') {
    return (
      <AuthScreenLayout
        title="Enter the code"
        subtitle={`We sent a 6-digit code to ${maskPhone(fullPhone)}.`}
        error={error}
        banner={notice}
        footer={backToSignIn}
      >
        <TextField
          label="Verification code"
          value={code}
          // No maxLength: a pasted "Your code is 123456" must reach sanitizeOtp intact.
          onChangeText={(v) => { setCode(sanitizeOtp(v)); setError(null); }}
          placeholder="123456"
          keyboardType="number-pad"
          textContentType="oneTimeCode"
          autoComplete="sms-otp"
        />
        <Button
          label={busy ? 'Verifying…' : 'Verify code'}
          onPress={() => void submitCode()}
          loading={busy}
          disabled={!codeValid}
        />
        <Button label="Resend code" variant="link" onPress={() => void resendCode()} disabled={busy} />
        <Button label="Start over" variant="link" onPress={startOver} disabled={busy} />
      </AuthScreenLayout>
    );
  }

  return (
    <AuthScreenLayout
      title="Choose a new password"
      subtitle="Your phone is verified. Pick a new password of at least 8 characters."
      error={error}
      footer={backToSignIn}
    >
      <TextField
        label="New password"
        value={password}
        onChangeText={(v) => { setPassword(v); setError(null); }}
        secure
        autoCapitalize="none"
        autoComplete="new-password"
        textContentType="newPassword"
        maxLength={72}
        error={password.length > 0 && !passwordLongEnough ? 'Password must be at least 8 characters.' : null}
      />
      {password.length > 0 ? (
        <View style={styles.strengthWrap}>
          <View style={styles.strengthBars}>
            {[0, 1, 2, 3].map((i) => (
              <View
                key={i}
                style={[styles.strengthBar, { backgroundColor: i < strength.score ? c.primary : c.border }]}
              />
            ))}
          </View>
          <Text style={[styles.strengthLabel, { color: c.muted }]}>{strength.label}</Text>
        </View>
      ) : null}
      <TextField
        label="Confirm new password"
        value={confirmPassword}
        onChangeText={(v) => { setConfirmPassword(v); setError(null); }}
        secure
        autoCapitalize="none"
        autoComplete="new-password"
        textContentType="newPassword"
        maxLength={72}
        error={confirmPassword.length > 0 && confirmPassword !== password ? 'Passwords do not match.' : null}
      />
      <Button
        label={busy ? 'Resetting…' : 'Reset password'}
        onPress={() => void submitPassword()}
        loading={busy}
        disabled={!passwordLongEnough || !passwordsMatch}
      />
      <Button label="Start over" variant="link" onPress={startOver} disabled={busy} />
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  strengthWrap: { marginTop: -spacing.xs, marginBottom: spacing.sm },
  strengthBars: { flexDirection: 'row', gap: spacing.xs },
  strengthBar: { flex: 1, height: 4, borderRadius: 2 },
  strengthLabel: { fontSize: 12, marginTop: spacing.xs },
});
