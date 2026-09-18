import { useEffect } from 'react';
import { Alert, Linking } from 'react-native';
import { authApi } from '../api/endpoints';
import { parseAppLink } from '../lib/appLinks';
import { toUserMessage } from '../lib/apiError';

/**
 * Handles the emailed "https://app.fynora.net/verify-email?token=..." link (register(), and the
 * fresh one a Google sign-in conflict sends) when the app is installed -- the web VerifyEmail page
 * does exactly this on load: read the token, call POST /auth/verify-email, show the outcome.
 *
 * Unlike the email-change link this needs no navigator at all. The token alone is the proof (the
 * endpoint is unauthenticated), so it works identically signed out, signed in, or mid phone
 * verification -- the three mutually-exclusive trees RootNavigator swaps between -- and the result
 * is a native alert rather than a screen that only one of those trees could host.
 *
 * A token is attempted once per mount while it is in flight or after it succeeded, so the OS
 * re-delivering the launch URL can't fire a second request against a single-use token and turn a
 * success into a spurious "invalid or expired". A FAILED token is forgotten, so tapping the same
 * link again after a dropped connection retries instead of doing nothing.
 */
export function useEmailVerificationDeepLink() {
  useEffect(() => {
    const attempted = new Set<string>();

    async function handleUrl(url: string) {
      const link = parseAppLink(url);
      if (!link || link.path !== '/verify-email') return;

      const token = link.params.token;
      if (!token) {
        Alert.alert('Verification failed', 'No verification token found in the link.');
        return;
      }
      if (attempted.has(token)) return;
      attempted.add(token);

      try {
        await authApi.verifyEmail(token);
        Alert.alert('Email verified', "You're all set. If you were signing in with Google, you can go back and try again.");
      } catch (err) {
        attempted.delete(token);
        Alert.alert('Verification failed', toUserMessage(err, 'This verification link is invalid or has expired.'));
      }
    }

    void Linking.getInitialURL().then((url) => { if (url) void handleUrl(url); });
    const subscription = Linking.addEventListener('url', (event) => { void handleUrl(event.url); });
    return () => subscription.remove();
  }, []);
}
