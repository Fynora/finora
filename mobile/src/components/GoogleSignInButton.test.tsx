import { useEffect } from 'react';
import { render, fireEvent, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import { GoogleSignInButton, isGoogleSignInConfigured } from './GoogleSignInButton';
import { ThemeProvider, useThemeSetting } from '../theme';
import { reportHandledEvent } from '../lib/monitoring';

jest.mock('../lib/monitoring', () => ({
  reportHandledEvent: jest.fn(),
}));

const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;
const reportEvent = reportHandledEvent as jest.Mock;
const UNAVAILABLE = 'Sign in with Google is unavailable right now. Please try again later.';

/** A rejection shaped like the library's own: an Error carrying a `code`. */
function codedError(code: unknown, message = 'from the library') {
  return Object.assign(new Error(message), { code });
}

function renderButton(onCredential = jest.fn(), onError = jest.fn()) {
  const view = render(
    <ThemeProvider>
      <GoogleSignInButton onCredential={onCredential} onError={onError} />
    </ThemeProvider>
  );
  return { view, onCredential, onError };
}

describe('GoogleSignInButton', () => {
  const originalEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalEnv;
  });

  it('renders nothing when EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID is unset', () => {
    delete process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;
    expect(isGoogleSignInConfigured()).toBe(false);

    const { view } = renderButton();
    expect(view.queryByText('Sign in with Google')).toBeNull();
  });

  describe('when configured', () => {
    beforeEach(() => {
      process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
    });

    it('renders the button', () => {
      expect(isGoogleSignInConfigured()).toBe(true);
      const { view } = renderButton();
      expect(view.getByText('Sign in with Google')).toBeTruthy();
    });

    it('stays the white Google button in dark mode, not the blue one', async () => {
      // Dark mode is switched on the way a user would (AccountsCard.test.tsx's approach), and the
      // probe proves it really took effect before the colour is checked.
      let resolvedTheme: string | null = null;
      function DarkTheme({ children }: { children: React.ReactNode }) {
        const { setSetting, resolved } = useThemeSetting();
        useEffect(() => { setSetting('dark'); }, [setSetting]);
        resolvedTheme = resolved;
        return <>{children}</>;
      }
      const view = render(
        <ThemeProvider>
          <DarkTheme>
            <GoogleSignInButton onCredential={jest.fn()} onError={jest.fn()} />
          </DarkTheme>
        </ThemeProvider>
      );

      await waitFor(() => expect(resolvedTheme).toBe('dark'));
      expect(view.getByTestId('google-sign-in-button')).toHaveStyle({ backgroundColor: '#ffffff' });
      expect(view.getByText('Sign in with Google')).toHaveStyle({ color: '#1F1F1F' });
    });

    it('lays the G and the label out centred in a row, the same height and radius as the Apple button', () => {
      // The library's own button pins the logo to the left edge; this one is drawn to pair with
      // the centred AppleAuthenticationButton beneath it.
      const { view } = renderButton();
      expect(view.getByText('Sign in with Google')).toHaveStyle({ flexShrink: 1, textAlign: 'center' });
      expect(view.getByTestId('google-sign-in-button')).toHaveStyle({
        flexDirection: 'row',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: 48, // min, not fixed: the row grows with large accessibility text
        width: '100%',
      });
    });

    it('hands a successful credential straight to onCredential', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: 'a-real-looking-id-token', user: {}, scopes: [], serverAuthCode: null },
      } as never);

      const { view, onCredential } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(onCredential).toHaveBeenCalledWith('a-real-looking-id-token'));
      expect(mockedGoogleSignin.configure).toHaveBeenCalledWith({
        webClientId: 'test-web-client-id.apps.googleusercontent.com',
      });
    });

    it('clears the cached Google account before signing in, so the picker always appears', async () => {
      // The SDK silently reuses the last-used account. Without an explicit signOut() first, a
      // device with two Google accounts is locked to whichever signed in first, with no in-app way
      // to switch -- and if that account is one the backend refuses, there is no way out at all.
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: 'a-real-looking-id-token', user: {}, scopes: [], serverAuthCode: null },
      } as never);

      const { view, onCredential } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(onCredential).toHaveBeenCalled());
      expect(mockedGoogleSignin.signOut).toHaveBeenCalled();
      expect(mockedGoogleSignin.signOut.mock.invocationCallOrder[0]).toBeLessThan(
        mockedGoogleSignin.signIn.mock.invocationCallOrder[0]
      );
    });

    it('signs in anyway when there is no cached account to clear', async () => {
      // signOut() rejects when nobody has ever signed in on this device. That is the ordinary
      // first-run state, not an error -- it must not block the sign-in it precedes.
      mockedGoogleSignin.signOut.mockRejectedValueOnce(new Error('RNGoogleSignin: not signed in'));
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: 'first-run-token', user: {}, scopes: [], serverAuthCode: null },
      } as never);

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(onCredential).toHaveBeenCalledWith('first-run-token'));
      expect(onError).not.toHaveBeenCalled();
    });

    it('does nothing when the user cancels -- not an error', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({ type: 'cancelled', data: null } as never);

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(mockedGoogleSignin.signIn).toHaveBeenCalled());
      expect(onCredential).not.toHaveBeenCalled();
      expect(onError).not.toHaveBeenCalled();
    });

    it('reports onError when signIn throws for a reason other than cancellation', async () => {
      mockedGoogleSignin.signIn.mockRejectedValue(new Error('network down'));

      const { view, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() =>
        expect(onError).toHaveBeenCalledWith('Sign in with Google is unavailable right now. Please try again later.')
      );
    });

    it('reports onError when a successful response is missing an idToken', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: null, user: {}, scopes: [], serverAuthCode: null },
      } as never);

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() =>
        expect(onError).toHaveBeenCalledWith('Google sign-in did not return a credential. Please try again.')
      );
      expect(onCredential).not.toHaveBeenCalled();
      expect(reportEvent).toHaveBeenCalledWith('Google sign-in failed', 'google-sign-in', {
        stage: 'sign-in',
        code: 'NO_ID_TOKEN',
      });
    });

    // Placed after the credential test: GoogleSignin.configure() runs once per module load and
    // that test asserts on it, so it must own the file's first press.
    it('tints the button while a finger is down', () => {
      // Pressable's pressed flag is set by the responder system, not by fireEvent('pressIn'), so
      // the touch is delivered as a responder grant (release is deferred by Pressable's minimum
      // press duration, so only the down state is asserted).
      const { view } = renderButton();
      const button = view.getByTestId('google-sign-in-button');
      expect(button).toHaveStyle({ backgroundColor: '#ffffff' });
      fireEvent(button, 'responderGrant', { persist: () => {}, nativeEvent: { touches: [], changedTouches: [], pageX: 1, pageY: 1, locationX: 1, locationY: 1, timestamp: Date.now(), identifier: 1, target: 1 } });
      expect(button).toHaveStyle({ backgroundColor: '#f2f2f2' });
    });

    it('dims and blocks the button while a sign-in is in flight, like the Apple button', async () => {
      let finish: () => void = () => {};
      mockedGoogleSignin.signIn.mockReturnValue(new Promise<never>((resolve) => { finish = () => resolve({ type: 'cancelled', data: null } as never); }));

      const { view } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(view.getByTestId('google-sign-in-button')).toBeDisabled());
      finish();
      await waitFor(() => expect(view.getByTestId('google-sign-in-button')).toBeEnabled());
    });

    describe('reporting why it failed', () => {
      // Every failure shows the same generic message, so this report is the only record of the
      // cause. Each stage is checked separately: knowing it was Google and not our backend (or
      // the other way round) is half of the answer.
      beforeEach(() => {
        reportEvent.mockClear();
        mockedGoogleSignin.hasPlayServices.mockReset().mockResolvedValue(true);
        mockedGoogleSignin.signIn.mockReset();
      });

      async function pressAndWaitForError(onCredential = jest.fn()) {
        const { view, onError } = renderButton(onCredential);
        fireEvent.press(view.getByText('Sign in with Google'));
        await waitFor(() => expect(onError).toHaveBeenCalledWith(UNAVAILABLE));
      }

      it("reports Google's own status code when signIn fails", async () => {
        mockedGoogleSignin.signIn.mockRejectedValue(codedError('12500'));

        await pressAndWaitForError();

        expect(reportEvent).toHaveBeenCalledTimes(1);
        expect(reportEvent).toHaveBeenCalledWith('Google sign-in failed', 'google-sign-in', {
          stage: 'sign-in',
          code: '12500',
        });
      });

      it('reports the play-services stage when the Play Services check fails', async () => {
        mockedGoogleSignin.hasPlayServices.mockRejectedValue(codedError('PLAY_SERVICES_NOT_AVAILABLE'));

        await pressAndWaitForError();

        expect(mockedGoogleSignin.signIn).not.toHaveBeenCalled();
        expect(reportEvent).toHaveBeenCalledWith('Google sign-in failed', 'google-sign-in', {
          stage: 'play-services',
          code: 'PLAY_SERVICES_NOT_AVAILABLE',
        });
      });

      it('reports the credential stage when handing the token on throws', async () => {
        mockedGoogleSignin.signIn.mockResolvedValue({
          type: 'success',
          data: { idToken: 'a-real-looking-id-token', user: {}, scopes: [], serverAuthCode: null },
        } as never);
        const onCredential = jest.fn().mockRejectedValue(codedError('ERR_NETWORK'));

        await pressAndWaitForError(onCredential);

        expect(reportEvent).toHaveBeenCalledWith('Google sign-in failed', 'google-sign-in', {
          stage: 'credential',
          code: 'ERR_NETWORK',
        });
      });

      it("never sends the error's message, and sends a code only when it is a plain label", async () => {
        mockedGoogleSignin.signIn.mockRejectedValue(codedError('jane@example.com said no', 'jane@example.com'));

        await pressAndWaitForError();

        expect(reportEvent).toHaveBeenCalledWith('Google sign-in failed', 'google-sign-in', {
          stage: 'sign-in',
          code: 'other',
        });
        expect(JSON.stringify(reportEvent.mock.calls)).not.toContain('jane@example.com');
      });

      it("reports 'none' when the error carries no code at all", async () => {
        mockedGoogleSignin.signIn.mockRejectedValue(new Error('no code here'));

        await pressAndWaitForError();

        expect(reportEvent).toHaveBeenCalledWith('Google sign-in failed', 'google-sign-in', {
          stage: 'sign-in',
          code: 'none',
        });
      });

      it('reports nothing when the user cancels, whichever way the cancel arrives', async () => {
        mockedGoogleSignin.signIn
          .mockRejectedValueOnce(codedError('SIGN_IN_CANCELLED'))
          .mockResolvedValueOnce({ type: 'cancelled', data: null } as never);

        const { view, onError } = renderButton();
        fireEvent.press(view.getByText('Sign in with Google'));
        await waitFor(() => expect(mockedGoogleSignin.signIn).toHaveBeenCalledTimes(1));
        await waitFor(() => expect(view.getByText('Sign in with Google')).toBeEnabled());
        fireEvent.press(view.getByText('Sign in with Google'));
        await waitFor(() => expect(mockedGoogleSignin.signIn).toHaveBeenCalledTimes(2));
        await waitFor(() => expect(view.getByText('Sign in with Google')).toBeEnabled());

        expect(onError).not.toHaveBeenCalled();
        expect(reportEvent).not.toHaveBeenCalled();
      });

      it('reports nothing when sign-in succeeds', async () => {
        mockedGoogleSignin.signIn.mockResolvedValue({
          type: 'success',
          data: { idToken: 'a-real-looking-id-token', user: {}, scopes: [], serverAuthCode: null },
        } as never);

        const { view, onCredential } = renderButton();
        fireEvent.press(view.getByText('Sign in with Google'));

        await waitFor(() => expect(onCredential).toHaveBeenCalled());
        expect(reportEvent).not.toHaveBeenCalled();
      });
    });
  });
});
