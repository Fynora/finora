import { Platform } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as AppleAuthentication from 'expo-apple-authentication';
import { AppleReauthPrompt } from './AppleReauthPrompt';
import { ThemeProvider } from '../theme';

function renderPrompt(onCredential = jest.fn(), onError = jest.fn()) {
  render(
    <ThemeProvider>
      <AppleReauthPrompt onCredential={onCredential} onError={onError} />
    </ThemeProvider>
  );
  return { onCredential, onError };
}

/**
 * Phase 4. AppleSignInButton itself already has full SDK-level coverage
 * (AppleSignInButton.test.tsx) -- this only pins what THIS wrapper adds: the explanatory copy, and
 * the explicit off-iOS fallback. Mobile-only, unlike GoogleReauthPrompt -- web has no Apple
 * Sign-In at all, so there is no web precedent this ports.
 */
describe('AppleReauthPrompt', () => {
  const originalOS = Platform.OS;

  afterEach(() => {
    Platform.OS = originalOS;
  });

  // An APPLE-method account originally signed up on an iPhone, but nothing stops the same account
  // being opened on Android too -- AppleSignInButton itself renders nothing off iOS, which would
  // otherwise leave that person looking at "verify your identity" with no interactive control
  // anywhere underneath, the same silent-lockout bug class GoogleReauthPrompt's unconfigured
  // fallback exists to avoid.
  it('shows an explanatory message instead of a dead-end control off iOS', () => {
    Platform.OS = 'android';

    renderPrompt();

    expect(screen.getByText(/needs an iPhone or iPad/i)).toBeTruthy();
    expect(screen.queryByText('Sign in with Apple')).toBeNull();
  });

  describe('on iOS', () => {
    beforeEach(() => {
      Platform.OS = 'ios';
    });

    it('explains why Apple is being asked for, and renders the button', () => {
      renderPrompt();

      expect(screen.getByText(/This account signs in with Apple/i)).toBeTruthy();
      expect(screen.getByText('Sign in with Apple')).toBeTruthy();
    });

    it('hands a successful credential straight through to onCredential', async () => {
      jest.mocked(AppleAuthentication.signInAsync).mockResolvedValue({
        identityToken: 'a-real-looking-identity-token', fullName: null,
      } as never);
      const { onCredential } = renderPrompt();

      fireEvent.press(screen.getByText('Sign in with Apple'));

      await waitFor(() => expect(onCredential).toHaveBeenCalledWith('a-real-looking-identity-token', undefined));
    });
  });
});
