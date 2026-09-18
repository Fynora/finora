import { useCallback, useEffect, useRef } from 'react';
import { Alert, Linking } from 'react-native';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { parseAppLink } from '../lib/appLinks';
import type { RootParamList } from './types';

/**
 * A reset link, or null for any other URL. `token` is null when the link is a reset link but carries
 * none (a truncated paste, a mail client that mangled the query) -- the caller tells the user rather
 * than leaving them with an app that silently did nothing.
 */
export function parseResetPasswordDeepLink(url: string): { token: string | null } | null {
  const link = parseAppLink(url);
  if (!link || link.path !== '/reset-password') return null;
  return { token: link.params.token || null };
}

interface Options {
  /** AuthContext's session restore is still running: signed-in vs signed-out is not known yet. */
  bootstrapping: boolean;
  signedIn: boolean;
  signOut: () => void;
}

/**
 * Opens ResetPasswordScreen from the emailed /reset-password?token=... link (or finora://).
 *
 * The screen lives in AuthStack, which only exists while signed out, so the link is held in a ref and
 * replayed once the right tree is mounted -- the same imperative shape as useReferralDeepLink, for
 * the same reason (React Navigation's declarative linking can only resolve against the tree that
 * happens to be mounted when the URL arrives).
 *
 * A phone that is still signed in needs a decision rather than a silent navigation: the reset
 * revokes every session anyway, and without an explicit way in the link would open the app and do
 * nothing -- with the OS now claiming the URL there is no browser to fall back to. So it asks once,
 * and on confirm signs out and then opens the screen. Cancelling drops the link, so a later
 * unrelated sign-out cannot resurrect it.
 *
 * Nothing here logs or stores the token; it goes into the route params and nowhere else.
 */
export function useResetPasswordDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  options: Options,
) {
  const pendingRef = useRef<string | null>(null);
  // True while the confirm alert is open for the pending token, so a re-delivered URL doesn't stack
  // a second one.
  const promptedRef = useRef(false);
  const optionsRef = useRef(options);

  useEffect(() => {
    optionsRef.current = options;
  });

  const settle = useCallback(() => {
    const token = pendingRef.current;
    if (!token) return;
    const { bootstrapping, signedIn } = optionsRef.current;
    if (bootstrapping) return;

    if (signedIn) {
      if (promptedRef.current) return;
      promptedRef.current = true;
      const drop = () => {
        pendingRef.current = null;
        promptedRef.current = false;
      };
      Alert.alert(
        'Reset your password?',
        "You're signed in. Resetting your password signs you out on all your devices.",
        [
          { text: 'Cancel', style: 'cancel', onPress: drop },
          { text: 'Sign out and continue', style: 'destructive', onPress: () => optionsRef.current.signOut() },
        ],
        { cancelable: true, onDismiss: drop },
      );
      return;
    }

    if (!navigationRef.current || !navigationRef.isReady()) return;
    pendingRef.current = null;
    promptedRef.current = false;
    navigationRef.navigate('ResetPassword', { token });
  }, [navigationRef]);

  useEffect(() => {
    function handleUrl(url: string) {
      const parsed = parseResetPasswordDeepLink(url);
      if (!parsed) return;
      if (!parsed.token) {
        Alert.alert('Reset link incomplete', 'This reset link is missing its token. Request a new one from the sign-in screen.');
        return;
      }
      pendingRef.current = parsed.token;
      settle();
    }

    void Linking.getInitialURL().then((url) => { if (url) handleUrl(url); });
    const subscription = Linking.addEventListener('url', (event) => handleUrl(event.url));
    return () => subscription.remove();
  }, [settle]);

  useEffect(() => {
    settle();
  }, [options.bootstrapping, options.signedIn, settle]);

  // Wired to NavigationContainer's onReady: isReady() can still be false the first time the
  // signed-out stack mounts.
  return { onNavigationReady: settle };
}
