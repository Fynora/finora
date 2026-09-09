import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import { GoogleReauthPrompt } from './GoogleReauthPrompt';
import { ThemeProvider } from '../theme';

const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

function renderPrompt(onCredential = jest.fn(), onError = jest.fn()) {
  render(
    <ThemeProvider>
      <GoogleReauthPrompt onCredential={onCredential} onError={onError} />
    </ThemeProvider>
  );
  return { onCredential, onError };
}

/**
 * Phase 4. GoogleSignInButton itself already has full SDK-level coverage
 * (GoogleSignInButton.test.tsx) -- this only pins what THIS wrapper adds: the explanatory copy, and
 * the explicit unconfigured fallback (mirrors the same bug frontend/src/components/
 * GoogleReauthPrompt.tsx's own doc comment already found once: the underlying button degrades to
 * rendering nothing at all when unconfigured, which would otherwise leave a GOOGLE-method user
 * looking at "verify your identity" with no interactive control anywhere underneath).
 */
describe('GoogleReauthPrompt', () => {
  const originalEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalEnv;
  });

  it('shows an explanatory message instead of a dead-end control when unconfigured', () => {
    delete process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

    renderPrompt();

    expect(screen.getByText(/Sign in with Google isn.t available right now/i)).toBeTruthy();
    expect(screen.queryByText('Sign in with Google')).toBeNull();
  });

  describe('when configured', () => {
    beforeEach(() => {
      process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
    });

    it('explains why Google is being asked for, and renders the button', () => {
      renderPrompt();

      expect(screen.getByText(/This account signs in with Google/i)).toBeTruthy();
      expect(screen.getByText('Sign in with Google')).toBeTruthy();
    });

    it('hands a successful credential straight through to onCredential', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: 'a-real-looking-id-token', user: {}, scopes: [], serverAuthCode: null },
      } as never);
      const { onCredential } = renderPrompt();

      fireEvent.press(screen.getByText('Sign in with Google'));

      await waitFor(() => expect(onCredential).toHaveBeenCalledWith('a-real-looking-id-token'));
    });
  });
});
