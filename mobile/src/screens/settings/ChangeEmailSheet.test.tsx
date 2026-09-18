import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import * as AppleAuthentication from 'expo-apple-authentication';
import { ChangeEmailSheet } from './ChangeEmailSheet';
import { emailChangeApi } from '../../api/endpoints';
import { AUTH_EMAIL_ALREADY_REGISTERED } from '../../api/errorCodes';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  emailChangeApi: { start: jest.fn() },
}));

const mockLogout = jest.fn();
jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ logout: mockLogout }),
}));

const api = emailChangeApi as jest.Mocked<typeof emailChangeApi>;
const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

const onClose = jest.fn();

/** Matches what apiErrorCode() reads -- same shape VerifyPhoneScreen.test.tsx's own
 *  phoneAlreadyRegisteredError() helper uses for the phone equivalent of this code. */
function emailAlreadyRegisteredError() {
  return {
    isAxiosError: true,
    response: {
      status: 409,
      data: { errorCode: AUTH_EMAIL_ALREADY_REGISTERED, message: 'An account with this email already exists.' },
    },
  };
}

function renderSheet(signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE' = 'PASSWORD') {
  // GoogleReauthPrompt/AppleReauthPrompt render GoogleSignInButton/AppleSignInButton, which read
  // useThemeSetting() -- unlike useTheme(), that hook throws without a real ThemeProvider (see
  // ThemeContext.tsx), so this wrapper is needed even though ChangeEmailSheet itself only ever
  // called the more permissive useTheme().
  return render(
    <ThemeProvider>
      <ChangeEmailSheet onClose={onClose} signInMethod={signInMethod} />
    </ThemeProvider>
  );
}

async function settle() {
  await act(async () => {});
}

function fillForm() {
  fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');
  fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
}

describe('ChangeEmailSheet', () => {
  beforeEach(() => {
    onClose.mockReset();
    mockLogout.mockReset();
    api.start.mockReset().mockResolvedValue({ sessionId: 'sess-1', devVerifyLink: null });
  });

  it('disables submission until both the new email and current password are filled', () => {
    renderSheet();

    expect(screen.getByRole('button', { name: /Send confirmation link/ })).toBeDisabled();

    fillForm();

    expect(screen.getByRole('button', { name: /Send confirmation link/ })).not.toBeDisabled();
  });

  it('calls emailChangeApi.start with the password-only step-up shape and shows the "check your inbox" state', async () => {
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(api.start).toHaveBeenCalledWith('CurrentPw1!', null, null, 'new@example.com');
    expect(screen.getByText('Check your inbox')).toBeTruthy();
    expect(screen.getByText(/We sent a confirmation link to new@example.com/)).toBeTruthy();
    // The form is gone -- this is a replacement step, not an overlay on top of it.
    expect(screen.queryByLabelText('Current password')).toBeNull();
  });

  it('shows a server error and stays on the form when start() fails', async () => {
    api.start.mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { message: 'Incorrect password.' } },
    });
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(screen.getByText('Incorrect password.')).toBeTruthy();
    expect(screen.queryByText('Check your inbox')).toBeNull();
    // An ordinary rejection (wrong password) must not offer "Log in instead" -- only the one
    // specific, known-actionable cause below does.
    expect(screen.queryByText('Log in instead')).toBeNull();
  });

  /** The email belongs to a different account -- ending the CURRENT session so the one that
   *  already owns it can be signed into is the actual fix, same reasoning as VerifyPhoneScreen's
   *  identical nudge for the phone case (#1645). */
  it('offers "Log in instead" when the new email already belongs to another account', async () => {
    api.start.mockRejectedValue(emailAlreadyRegisteredError());
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(screen.getByText('An account with this email already exists.')).toBeTruthy();
    fireEvent.press(screen.getByText('Log in instead'));
    expect(mockLogout).toHaveBeenCalled();
  });

  it('clears "Log in instead" once the email is edited, not left dangling under a stale error', async () => {
    api.start.mockRejectedValue(emailAlreadyRegisteredError());
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();
    expect(screen.getByText('Log in instead')).toBeTruthy();

    fireEvent.changeText(screen.getByLabelText('New email address'), 'different@example.com');

    expect(screen.queryByText('Log in instead')).toBeNull();
  });

  it('shows the dev-only verify link as plain copyable text when the backend returns one', async () => {
    api.start.mockResolvedValue({
      sessionId: 'sess-1',
      devVerifyLink: 'finora://email-change-verify?sessionId=sess-1&token=raw-token',
    });
    renderSheet();
    fillForm();

    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    expect(screen.getByText('finora://email-change-verify?sessionId=sess-1&token=raw-token')).toBeTruthy();
  });

  it('closes via the Cancel link before submitting', () => {
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
  });

  it('closes via the Done button after a successful start', async () => {
    renderSheet();
    fillForm();
    fireEvent.press(screen.getByRole('button', { name: /Send confirmation link/ }));
    await settle();

    fireEvent.press(screen.getByRole('button', { name: 'Done' }));

    expect(onClose).toHaveBeenCalled();
  });
});

// Phase 4 (Medium-Tier Parity). Same groundwork as ChangePasswordSheet's identical step-up --
// GoogleReauthPrompt/AppleReauthPrompt already have their own dedicated coverage, so this only
// pins what THIS sheet does with them: the password field is replaced, entering a reauth
// credential is gated on a valid new email already being typed, and a successful credential is
// forwarded to emailChangeApi.start in the right argument slot.
describe('Google/Apple reauth step-up (Phase 4)', () => {
  const originalGoogleEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  beforeEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
  });

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalGoogleEnv;
  });

  it('asks for the new email before offering Google reauth, for a GOOGLE-method account', () => {
    renderSheet('GOOGLE');

    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.getByText('Enter a valid new email address above to continue.')).toBeTruthy();
    expect(screen.queryByText('Sign in with Google')).toBeNull();

    fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');

    expect(screen.getByText('Sign in with Google')).toBeTruthy();
  });

  it('starts the session with a fresh Google credential in place of a password', async () => {
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');
    fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(api.start).toHaveBeenCalledWith(null, 'a-google-id-token', null, 'new@example.com');
    expect(screen.getByText('Check your inbox')).toBeTruthy();
  });

  it('asks for the new email before offering Apple reauth, for an APPLE-method account', () => {
    renderSheet('APPLE');

    expect(screen.queryByLabelText('Current password')).toBeNull();
    expect(screen.getByText('Enter a valid new email address above to continue.')).toBeTruthy();
    expect(screen.queryByText('Sign in with Apple')).toBeNull();

    fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');

    expect(screen.getByText('Sign in with Apple')).toBeTruthy();
  });

  it('starts the session with a fresh Apple credential in place of a password', async () => {
    jest.mocked(AppleAuthentication.signInAsync).mockResolvedValue({
      identityToken: 'an-apple-id-token', fullName: null,
    } as never);
    renderSheet('APPLE');
    fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');

    fireEvent.press(screen.getByText('Sign in with Apple'));
    await settle();

    expect(api.start).toHaveBeenCalledWith(null, null, 'an-apple-id-token', 'new@example.com');
    expect(screen.getByText('Check your inbox')).toBeTruthy();
  });

  it("explains the failure in terms of the account's sign-in method when reauth verification is rejected", async () => {
    // No response body, so toUserMessage falls through to this component's own fallback message
    // rather than a server-provided one -- that fallback is exactly what this test pins.
    api.start.mockRejectedValue(new Error('network blip'));
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');
    fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(await screen.findByText(/couldn't verify your Google account/i)).toBeTruthy();
    expect(screen.queryByText('Check your inbox')).toBeNull();
  });

  it('offers "Log in instead" for a GOOGLE-method account too, and clears it if a later reauth attempt fails for an unrelated reason', async () => {
    api.start.mockRejectedValue(emailAlreadyRegisteredError());
    mockedGoogleSignin.signIn.mockResolvedValue({
      type: 'success',
      data: { idToken: 'a-google-id-token', user: {}, scopes: [], serverAuthCode: null },
    } as never);
    renderSheet('GOOGLE');
    fireEvent.changeText(screen.getByLabelText('New email address'), 'new@example.com');

    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(screen.getByText('An account with this email already exists.')).toBeTruthy();
    expect(screen.getByText('Log in instead')).toBeTruthy();

    // A later reauth attempt that fails at the SIGN-IN step itself (GoogleReauthPrompt's own
    // onError, a completely different failure surface from api.start rejecting above) must not
    // leave "Log in instead" dangling under an error it has nothing to do with.
    mockedGoogleSignin.signIn.mockRejectedValue(new Error('native cancel'));
    fireEvent.press(screen.getByText('Sign in with Google'));
    await settle();

    expect(screen.getByText('Sign in with Google is unavailable right now. Please try again later.')).toBeTruthy();
    expect(screen.queryByText('Log in instead')).toBeNull();
  });
});
