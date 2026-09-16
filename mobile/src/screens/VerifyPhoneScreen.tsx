import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { phoneApi, phoneChangeApi, userApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import {
  confirmPhoneVerificationCode,
  sendPhoneVerificationCode,
  type PhoneConfirmation,
} from '../lib/phoneAuth';
import { maskPhone } from '../lib/maskPhone';
import { toUserMessage } from '../lib/apiError';
import { reportHandledError } from '../lib/monitoring';
import { PHONE_PATTERN, sanitizeOtp, sanitizePhoneNumber } from '../lib/validation';
import { spacing, useTheme } from '../theme';

// Purely a client-side courtesy against accidental double-clicks -- Firebase's own
// auth/too-many-requests is the real rate limit, this just avoids racking those up needlessly.
const RESEND_COOLDOWN_SECONDS = 30;

/**
 * Ported from frontend/src/pages/VerifyPhone.tsx. The flow is identical apart from what the web
 * version needed for reCAPTCHA: no container id is passed to sendPhoneVerificationCode(), there's
 * no hidden anchor element to render, and there's no resetPhoneVerification() cleanup on unmount
 * -- @react-native-firebase/auth verifies the app natively instead (see src/lib/phoneAuth.ts).
 */
export function VerifyPhoneScreen() {
  const c = useTheme();
  const { setPhoneVerified, logout } = useAuth();
  const [otp, setOtp] = useState('');
  // Kept as two separate states, matching web -- a failed *send* means there's no code at all and
  // the user needs a real way out (the Change number / Log out escape hatch below); a failed
  // *verify* means a code did arrive and the fix is just retyping it.
  const [sendError, setSendError] = useState<string | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [phoneNumber, setPhoneNumber] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);
  const startedRef = useRef(false);

  // The "Change Number" detour: for a user whose account phone number is wrong/unreachable (the
  // sendError escape hatch below), or who has no number on file at all yet -- a Google/Apple
  // sign-up (see AuthService.createOAuthUserRecord's own doc comment: phoneNumber is left null
  // there). Same form either way (PhoneChangeService.start() accepts both), but the copy and the
  // presence of a "Back" control differ: there is no working `verify` state to go back to when
  // there was never a number to verify in the first place.
  const [mode, setMode] = useState<'verify' | 'enterNewNumber' | 'confirmNewNumber'>('verify');
  const [numberMissing, setNumberMissing] = useState(false);
  const [newLocalNumber, setNewLocalNumber] = useState('');
  const [newNumberTouched, setNewNumberTouched] = useState(false);
  const [changeSessionId, setChangeSessionId] = useState<string | null>(null);
  const [changeMaskedPhone, setChangeMaskedPhone] = useState<string | null>(null);
  const [changeConfirmation, setChangeConfirmation] = useState<PhoneConfirmation | null>(null);
  const [changeOtp, setChangeOtp] = useState('');
  const [changeError, setChangeError] = useState<string | null>(null);
  const [changeSubmitting, setChangeSubmitting] = useState(false);
  const [changeResendCooldown, setChangeResendCooldown] = useState(0);

  // Ticks the resend cooldown down to 0 once a second. Only runs while there's actually a
  // cooldown in progress, so this is a no-op for almost the entire life of the screen.
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const id = setInterval(() => setResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [resendCooldown]);

  // Same cooldown mechanism, scoped separately to the Change Number sub-flow's own "Send code"
  // control -- a distinct piece of state rather than reusing resendCooldown above, since the two
  // are independent controls that can each be mid-cooldown on their own schedule.
  useEffect(() => {
    if (changeResendCooldown <= 0) return;
    const id = setInterval(() => setChangeResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [changeResendCooldown]);

  // isUserInitiated distinguishes an explicit "Resend" tap from the automatic send on mount --
  // only a tap starts the cooldown below.
  async function startVerification(isUserInitiated = false) {
    setSending(true);
    setSendError(null);
    setVerifyError(null);
    setOtp('');
    try {
      // The account's real phone number is never carried through navigation params -- fetched
      // fresh here (this screen is only ever reached authenticated) and handed straight to
      // Firebase, which sends the code itself; this backend never does.
      const settings = await userApi.get();
      setPhoneNumber(settings.phoneNumber);
      // Bug fix: a Google/Apple sign-up reaches this screen with NO phone number on file at all
      // (AuthService.createOAuthUserRecord leaves it null) -- there is nothing to send a code to
      // yet. Unconditionally calling sendPhoneVerificationCode(null) crashed Firebase's own
      // Android SDK with a native NullPointerException ("Attempt to invoke virtual method
      // 'boolean java.lang.String.equals(java.lang.Object)' on a null object reference"), caught
      // live via Sentry on a real tester's device. Route straight into the same number-entry form
      // the sendError escape hatch below already provides, instead of attempting (and crashing on)
      // a send with nothing to send to.
      if (!settings.phoneNumber) {
        setNumberMissing(true);
        setMode('enterNewNumber');
        return;
      }
      const result = await sendPhoneVerificationCode(settings.phoneNumber);
      setConfirmation(result);
    } catch (err) {
      // The only remote visibility into this failure -- nothing else reports it (Firebase's own
      // client-SDK calls don't flow through Cloud IAM Data Access audit logs, which only cover
      // IAM-authenticated calls, not this API-key-authenticated one), and the real Firebase error
      // code was otherwise thrown away the moment toUserMessage() turned it into a user-facing
      // sentence below.
      reportHandledError(err, 'phone-verification-send');
      setSendError(toUserMessage(err, 'Could not send a verification code right now.'));
    } finally {
      setSending(false);
      if (isUserInitiated) setResendCooldown(RESEND_COOLDOWN_SECONDS);
    }
  }

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    void startVerification();
  }, []);

  function startChangingNumber() {
    setMode('enterNewNumber');
    // Deliberately does NOT touch numberMissing: this is also the handler for confirmNewNumber's
    // own "Didn't get a code? Change number" retry, reachable from EITHER the ordinary
    // sendError escape hatch (numberMissing already false) or the Google/Apple first-time-set
    // flow (numberMissing already true -- and must stay true, or the form wrongly offers a "Back"
    // button into a `verify` state that was never actually reached).
    setNewLocalNumber('');
    setNewNumberTouched(false);
    setChangeError(null);
    setChangeResendCooldown(0);
  }

  /** Step 1 of the detour: open a PhoneChangeSession server-side, then have Firebase send a code
   *  to the NEW number directly -- same pattern as startVerification() above, just against
   *  phoneChangeApi.start() instead of userApi.get(). */
  async function handleStartPhoneChange() {
    if (!PHONE_PATTERN.test(newLocalNumber)) {
      setNewNumberTouched(true);
      return;
    }
    const requestedNumber = `+91${newLocalNumber}`;
    setChangeSubmitting(true);
    setChangeError(null);
    try {
      const start = await phoneChangeApi.start(requestedNumber);
      const result = await sendPhoneVerificationCode(requestedNumber);
      setChangeSessionId(start.sessionId);
      setChangeMaskedPhone(start.maskedPhone);
      setChangeConfirmation(result);
      setChangeOtp('');
      setChangeResendCooldown(RESEND_COOLDOWN_SECONDS);
      setMode('confirmNewNumber');
    } catch (err) {
      reportHandledError(err, 'verify-phone-change-number-send-otp');
      setChangeError(toUserMessage(err, 'Could not send a verification code right now.'));
    } finally {
      setChangeSubmitting(false);
    }
  }

  /** Step 2 of the detour: confirm the code against the NEW number, then commit it -- verifyOtp()
   *  proves control of that number server-side, complete() writes it onto the account and marks
   *  phoneVerified, same as the normal verify flow's own phoneApi.verify() does for the original
   *  number. */
  async function handleConfirmPhoneChange() {
    if (!changeConfirmation || !changeSessionId) return;
    setChangeError(null);
    setChangeSubmitting(true);
    try {
      const idToken = await confirmPhoneVerificationCode(changeConfirmation, changeOtp);
      await phoneChangeApi.verifyOtp(changeSessionId, idToken);
      const completed = await phoneChangeApi.complete(changeSessionId);
      setPhoneNumber(completed.phoneNumber);
      // No navigation: flipping this flag is what moves RootNavigator to the app stack.
      setPhoneVerified(true);
    } catch (err) {
      setChangeError(toUserMessage(err, 'Could not verify — try again.'));
    } finally {
      setChangeSubmitting(false);
    }
  }

  async function handleVerify() {
    if (!confirmation) return;
    setVerifyError(null);
    setLoading(true);
    try {
      const idToken = await confirmPhoneVerificationCode(confirmation, otp);
      await phoneApi.verify(idToken);
      // No navigation: flipping this flag is what moves RootNavigator to the app stack.
      setPhoneVerified(true);
    } catch (err) {
      setVerifyError(toUserMessage(err, 'Could not verify — try again.'));
    } finally {
      setLoading(false);
    }
  }

  // Found live: this used to read "Enter the 6-digit code we sent to ..." unconditionally, even
  // while startVerification's own send was still in flight -- a past-tense claim that hadn't
  // happened yet. Firebase's phone-auth send (reCAPTCHA + the API call itself, worse for +91
  // numbers) can take a while to even resolve, so a user watching this screen during that wait
  // saw a message telling them something already occurred. State-aware now, matching
  // frontend/src/pages/VerifyPhone.tsx's own subtitle logic -- and once a code genuinely has been
  // sent, sets the expectation that arrival itself can also take a minute or two, rather than
  // leaving the user to guess whether "Resend" is the right move.
  const maskedNumber = phoneNumber ? maskPhone(phoneNumber) : 'your mobile number';
  const verifySubtitle = confirmation
    ? `Enter the 6-digit code we sent to ${maskedNumber}. This can take a minute or two to arrive.`
    : sending
      ? 'Sending a verification code to your mobile number…'
      : 'We ran into a problem starting verification.';

  if (mode === 'enterNewNumber') {
    return (
      <AuthScreenLayout
        title={numberMissing ? 'Add your phone number' : 'Change your number'}
        subtitle={
          numberMissing
            ? "Your account doesn't have a mobile number on file yet. We'll send a code to confirm it's yours."
            : "Enter the mobile number you'd like to use instead. We'll send a code to confirm it's yours before updating your account."
        }
        error={changeError}
        footer={
          numberMissing ? (
            // No "Back" here -- unlike the ordinary Change Number entry, there is no working
            // `verify` state to return to: this account never had a number to attempt sending a
            // code to in the first place (see startVerification's own comment above).
            <Button label="Sign out" variant="link" onPress={logout} />
          ) : (
            <Button label="Back" variant="link" onPress={() => setMode('verify')} />
          )
        }
      >
        <TextField
          label="New mobile number"
          value={newLocalNumber}
          onChangeText={(v) => setNewLocalNumber(sanitizePhoneNumber(v))}
          onBlur={() => setNewNumberTouched(true)}
          placeholder="XXXXXXXXXX"
          prefix="+91"
          keyboardType="number-pad"
          autoComplete="tel"
          returnKeyType="go"
          onSubmitEditing={handleStartPhoneChange}
          error={
            newNumberTouched && !PHONE_PATTERN.test(newLocalNumber)
              ? 'Enter a valid 10-digit mobile number (no leading 0-5).'
              : null
          }
        />

        <Button
          label={changeResendCooldown > 0 ? `Send code in ${changeResendCooldown}s` : 'Send code'}
          onPress={handleStartPhoneChange}
          loading={changeSubmitting}
          disabled={changeSubmitting || changeResendCooldown > 0}
        />
      </AuthScreenLayout>
    );
  }

  if (mode === 'confirmNewNumber') {
    return (
      <AuthScreenLayout
        title="Confirm your number"
        subtitle={`Enter the 6-digit code we sent to ${changeMaskedPhone ?? 'your new number'}.`}
        error={changeError}
        footer={<Button label="Didn't get a code? Change number" variant="link" onPress={startChangingNumber} />}
      >
        <TextField
          label="Verification code"
          value={changeOtp}
          onChangeText={(v) => setChangeOtp(sanitizeOtp(v))}
          placeholder="123456"
          keyboardType="number-pad"
          autoComplete="sms-otp"
          textContentType="oneTimeCode"
          returnKeyType="go"
          onSubmitEditing={handleConfirmPhoneChange}
        />

        <Button
          label="Confirm number"
          onPress={handleConfirmPhoneChange}
          loading={changeSubmitting}
          disabled={changeSubmitting || changeOtp.length !== 6}
        />
      </AuthScreenLayout>
    );
  }

  return (
    <AuthScreenLayout
      title="Verify your phone"
      subtitle={verifySubtitle}
      error={verifyError}
      footer={
        // The web app reaches this screen mid-navigation and can always go back; here it's the
        // only screen in its stack (an unverified account can't reach anything else), so without
        // this there'd be no way out except uninstalling. Signing out returns to Login.
        <Button label="Sign out" variant="link" onPress={logout} />
      }
    >
      {sendError ? (
        <View style={styles.sendErrorBox}>
          <Text style={[styles.sendErrorText, { color: c.danger }]}>{sendError}</Text>
          <View style={styles.sendErrorActions}>
            <Button label="Change number" variant="link" onPress={startChangingNumber} />
            <Button label="Log out and try again later" variant="link" onPress={logout} />
          </View>
        </View>
      ) : null}

      <TextField
        label="Verification code"
        value={otp}
        onChangeText={(v) => setOtp(sanitizeOtp(v))}
        placeholder="123456"
        keyboardType="number-pad"
        autoComplete="sms-otp"
        textContentType="oneTimeCode"
        // No maxLength, for the same reason as RegisterScreen's phone field: RN would apply it to
        // pasted text, so pasting a whole SMS ("Your code is 123456") would be cut to "Your c"
        // and then stripped to nothing. The onChangeText handler already digit-filters and caps.
        editable={!!confirmation}
        returnKeyType="go"
        onSubmitEditing={handleVerify}
      />

      <Button
        label="Verify"
        onPress={handleVerify}
        loading={loading}
        disabled={!confirmation || otp.length !== 6}
      />

      <View style={styles.resendRow}>
        <Button
          label={
            sending
              ? 'Sending…'
              : resendCooldown > 0
                ? `${sendError ? 'Try again' : 'Resend'} in ${resendCooldown}s`
                : sendError
                  ? 'Try again'
                  : "Didn't get a code? Resend"
          }
          variant="link"
          onPress={() => startVerification(true)}
          disabled={sending || resendCooldown > 0}
        />
      </View>

      <Text style={[styles.hint, { color: c.mutedInk }]}>
        Verification is required before you can use your account.
      </Text>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  resendRow: {
    marginTop: spacing.md,
  },
  hint: {
    fontSize: 11,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  sendErrorBox: {
    marginBottom: spacing.md,
  },
  sendErrorText: {
    fontSize: 13,
    marginBottom: spacing.xs,
  },
  sendErrorActions: {
    flexDirection: 'row',
    gap: spacing.md,
  },
});
