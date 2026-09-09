import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import * as AppleAuthentication from 'expo-apple-authentication';
import { ChangePasswordSheet, nextPasswordSuggestion, passwordStrengthMeter } from './ChangePasswordSheet';
import { passwordChangeApi } from '../../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode } from '../../lib/phoneAuth';
import { safeStorage } from '../../lib/safeStorage';
import { ThemeProvider } from '../../theme';

const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

jest.mock('../../api/endpoints', () => ({
  passwordChangeApi: { start: jest.fn(), verifyOtp: jest.fn(), complete: jest.fn() },
}));

jest.mock('../../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: jest.fn(),
  confirmPhoneVerificationCode: jest.fn(),
}));

const api = passwordChangeApi as jest.Mocked<typeof passwordChangeApi>;
const sendCode = sendPhoneVerificationCode as jest.MockedFunction<typeof sendPhoneVerificationCode>;
const confirmCode = confirmPhoneVerificationCode as jest.MockedFunction<typeof confirmPhoneVerificationCode>;

// Invented, matching the value the rest of this suite uses. Declared once so the hygiene marker
// sits in one place rather than on every line that mentions it.
const PHONE = '+919876543210'; // synthetic-ok: invented test number
const MASKED_PHONE = '+•••••••••210';

const onClose = jest.fn();
const onSuccess = jest.fn();

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  // GoogleReauthPrompt/AppleReauthPrompt render GoogleSignInButton/AppleSignInButton, which read
  // useThemeSetting() -- unlike useTheme(), that hook throws without a real ThemeProvider (see
  // ThemeContext.tsx), so this wrapper is needed even though ChangePasswordSheet itself only ever
  // called the more permissive useTheme().
  return render(
    <ThemeProvider>
      <ChangePasswordSheet onClose={onClose} onSuccess={onSuccess} signInMethod={signInMethod} />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/** Drives the flow up to the new-password step. */
async function reachNewPasswordStep() {
  fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
  fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
  await settle();
  fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
  fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
  await settle();
}

describe('ChangePasswordSheet', () => {
  beforeEach(async () => {
    onClose.mockReset();
    onSuccess.mockReset();
    api.start.mockReset().mockResolvedValue({
      sessionId: 'sess-1', phoneNumber: PHONE, maskedPhone: MASKED_PHONE,
    });
    api.verifyOtp.mockReset().mockResolvedValue({ message: 'ok' });
    api.complete.mockReset().mockResolvedValue({ message: 'Password updated.', otherDevicesSignedOut: true });
    sendCode.mockReset().mockResolvedValue({ confirm: jest.fn() } as never);
    confirmCode.mockReset().mockResolvedValue('firebase-id-token');
    await safeStorage.setItem('finora_refresh_token', 'refresh-abc');
  });

  it('starts by asking for the current password', () => {
    renderSheet();

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  /**
   * The backend never sends the code -- it returns the phone number and Firebase sends it. Getting
   * this backwards would look identical on screen and deliver nothing.
   */
  it('asks the backend to start, then has Firebase send the code', async () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    await waitFor(() => expect(api.start).toHaveBeenCalledWith('CurrentPw1!', null, null));
    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(await screen.findByLabelText('Verification code')).toBeTruthy();
    // Masked, never the full number.
    expect(screen.getByText(new RegExp(MASKED_PHONE.replace('+', '\\+')))).toBeTruthy();
  });

  it('stays on the first step and explains when the current password is rejected', async () => {
    api.start.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Current password is incorrect.' } },
      })
    );
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'wrong');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    expect(await screen.findByText('Current password is incorrect.')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
    expect(sendCode).not.toHaveBeenCalled();
  });

  it('sends the Firebase ID token to the backend, never the code itself', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();

    await waitFor(() => expect(api.verifyOtp).toHaveBeenCalledWith('sess-1', 'firebase-id-token'));
  });

  it('clears a rejected code so the field is ready for another attempt', async () => {
    confirmCode.mockReset().mockRejectedValue({ code: 'auth/invalid-verification-code' });
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.changeText(screen.getByLabelText('Verification code'), '111111');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();

    expect(await screen.findByText(/doesn't match/i)).toBeTruthy();
    expect(screen.getByLabelText('Verification code').props.value).toBe('');
  });

  it('completes with the session, the choice about other devices, and this device’s refresh token', async () => {
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'BrandNewPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Update Password/ }));
    await settle();

    await waitFor(() =>
      expect(api.complete).toHaveBeenCalledWith('sess-1', 'BrandNewPw1!', true, 'refresh-abc')
    );
    expect(onSuccess).toHaveBeenCalled();
    expect(await screen.findByText('Password updated')).toBeTruthy();
  });

  it('will not submit until both new-password fields match', async () => {
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'Different1!');
    await settle();

    expect(screen.getByRole('button', { name: /Update Password/ }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText("Passwords don't match.")).toBeTruthy();
  });

  it('lets the user keep other devices signed in', async () => {
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.press(screen.getByLabelText('Keep other devices signed in'));
    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'BrandNewPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Update Password/ }));
    await settle();

    await waitFor(() => expect(api.complete).toHaveBeenCalledWith('sess-1', 'BrandNewPw1!', false, 'refresh-abc'));
  });

  // Without the refresh token the backend cannot tell which session to spare, and signing out
  // "other" devices would include this one.
  it('refuses to complete when this device’s refresh token is missing', async () => {
    await safeStorage.removeItem('finora_refresh_token');
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'BrandNewPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Update Password/ }));
    await settle();

    expect(await screen.findByText(/session information is missing/i)).toBeTruthy();
    expect(api.complete).not.toHaveBeenCalled();
  });

  it('starts over back at the current-password step', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.press(screen.getByText(/Start over/));
    await settle();

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  // Otherwise a tap on "Start over" while verifyOtp() is still in flight resets step/session
  // state, and the stale call's success path (setStep('newPassword')) can land afterward and
  // yank the UI forward again with state the user just abandoned.
  it('disables Start over while verifyOtp() is in flight', async () => {
    let resolveVerify!: (v: { message: string }) => void;
    api.verifyOtp.mockReset().mockReturnValue(
      new Promise((resolve) => { resolveVerify = resolve; })
    );
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();

    expect(
      screen.getByRole('button', { name: /Start over/ }).props.accessibilityState.disabled
    ).toBe(true);

    await act(async () => { resolveVerify({ message: 'ok' }); });
  });
});

// Phase 4 (Medium-Tier Parity). A GOOGLE/APPLE-method account has no password to prove identity
// with -- see the component's own doc comment -- so the first step renders the matching reauth
// prompt instead of the password field, and starts the session with a fresh credential in its
// place. GoogleReauthPrompt/AppleReauthPrompt each already have their own dedicated coverage
// (GoogleReauthPrompt.test.tsx, AppleReauthPrompt.test.tsx); this only pins what THIS sheet does
// with them: which one renders, that the password field is absent, and that a successful
// credential is forwarded to passwordChangeApi.start in the right argument slot.
describe('Google/Apple reauth step-up (Phase 4)', () => {
  const originalGoogleEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  beforeEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
  });

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalGoogleEnv;
  });

  it('renders the Google reauth prompt, not a password field, for a GOOGLE-method account', () => {
    renderSheet('GOOGLE');

    expect(screen.getByText('Sign in with Google')).toBeTruthy();
    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.queryByText('Sign in with Apple')).toBeNull();
  });

  it('starts the session with a fresh Google credential in place of a password', async () => {
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    await waitFor(() => expect(api.start).toHaveBeenCalledWith(null, 'a-google-id-token', null));
    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(await screen.findByLabelText('Verification code')).toBeTruthy();
  });

  it('renders the Apple reauth prompt, not a password field, for an APPLE-method account', () => {
    renderSheet('APPLE');

    expect(screen.getByText('Sign in with Apple')).toBeTruthy();
    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.queryByText('Sign in with Google')).toBeNull();
  });

  it('starts the session with a fresh Apple credential in place of a password', async () => {
    jest.mocked(AppleAuthentication.signInAsync).mockResolvedValue({
      identityToken: 'an-apple-id-token', fullName: null,
    } as never);
    renderSheet('APPLE');

    fireEvent.press(screen.getByText('Sign in with Apple'));
    await settle();

    await waitFor(() => expect(api.start).toHaveBeenCalledWith(null, null, 'an-apple-id-token'));
    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(await screen.findByLabelText('Verification code')).toBeTruthy();
  });

  it("explains the failure in terms of the account's sign-in method when reauth verification is rejected", async () => {
    // No response body, so toUserMessage falls through to this component's own fallback message
    // rather than a server-provided one -- that fallback is exactly what this test pins.
    api.start.mockReset().mockRejectedValue(new Error('network blip'));
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(await screen.findByText(/couldn't verify your Google account/i)).toBeTruthy();
    expect(sendCode).not.toHaveBeenCalled();
  });
});

describe('password strength guidance', () => {
  // Only length is enforced server-side; this is a guide, so it must never read as a gate.
  it('scores from weak to strong', () => {
    expect(passwordStrengthMeter('').tone).toBe('none');
    expect(passwordStrengthMeter('abc').tone).toBe('weak');
    expect(passwordStrengthMeter('abcdefgh').tone).toBe('weak');
    expect(passwordStrengthMeter('Abcdefgh1').tone).toBe('good');
    expect(passwordStrengthMeter('Abcdefgh1!').tone).toBe('strong');
  });

  it('names the next concrete step rather than only a verdict', () => {
    expect(nextPasswordSuggestion('abcdefgh')).toMatch(/uppercase/i);
    expect(nextPasswordSuggestion('Abcdefgh')).toMatch(/number/i);
    expect(nextPasswordSuggestion('Abcdefgh1!')).toBeNull();
  });
});
