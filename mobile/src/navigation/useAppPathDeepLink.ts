import { useCallback, useEffect, useRef } from 'react';
import { Linking } from 'react-native';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { createLaunchUrlGuard, parseAppLink, pathIsUnder } from '../lib/appLinks';
import type { RootParamList } from './types';

// See createLaunchUrlGuard: a remount (RootErrorBoundary "Try again") must not replay the launch link.
const isFirstLaunchDelivery = createLaunchUrlGuard();

export type AppPathRoute = 'Settings' | 'Statements';

/**
 * Where an emailed web-app link lands in the app: the security alert links to /app/settings and
 * the statement-import emails to /app/imports/<jobId>. The app has no per-job screen, so every
 * import link goes to Statement History, which is where a finished or held import is listed (the
 * same destination a tapped IMPORT_STATEMENT_* push already uses). /app/billing is not here on
 * purpose -- see APP_LINK_PATH_PREFIXES in appLinks.config.js.
 */
export function parseAppPathDeepLink(url: string): AppPathRoute | null {
  const link = parseAppLink(url);
  if (!link) return null;
  // Exact, matching what is claimed: a deeper settings page has no mapping here, so it is ignored
  // rather than dropped on the Settings root.
  if (link.path === '/app/settings') return 'Settings';
  if (pathIsUnder(link.path, '/app/imports')) return 'Statements';
  return null;
}

/**
 * Same stash-until-ready shape as useEmailChangeDeepLink (see its doc comment for why an
 * imperative hook rather than React Navigation's declarative `linking.config`): the link can
 * arrive while signed out or mid phone verification, when the More stack these screens live in
 * isn't mounted, so it waits in a ref and is replayed the moment `ready` turns true. A real
 * sign-out drops a link still waiting, so it can't surprise whoever signs in next.
 *
 * `ready` is RootNavigator's isAppTabsActive; `signedIn` is `token !== null`, which -- unlike
 * `ready` -- doesn't dip on a mid-session phone re-verification challenge.
 */
export function useAppPathDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  ready: boolean,
  signedIn: boolean,
) {
  const pendingRef = useRef<AppPathRoute | null>(null);
  const readyRef = useRef(ready);
  const wasSignedInRef = useRef(signedIn);

  const tryConsume = useCallback(() => {
    if (!readyRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const route = pendingRef.current;
    if (!route) return;
    pendingRef.current = null;
    navigationRef.navigate('More', { screen: route });
  }, [navigationRef]);

  useEffect(() => {
    function handleUrl(url: string) {
      const route = parseAppPathDeepLink(url);
      if (!route) return;
      pendingRef.current = route;
      tryConsume();
    }

    // A mount torn down before this resolves must not claim the URL: its replacement is the one that
    // can act on it, and a dead mount's handler would consume the link against a stale navigation ref.
    let cancelled = false;
    void Linking.getInitialURL().then((url) => { if (!cancelled && url && isFirstLaunchDelivery(url)) handleUrl(url); });
    const subscription = Linking.addEventListener('url', (event) => handleUrl(event.url));
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, [tryConsume]);

  useEffect(() => {
    readyRef.current = ready;
    if (wasSignedInRef.current && !signedIn) pendingRef.current = null;
    wasSignedInRef.current = signedIn;
    tryConsume();
  }, [ready, signedIn, tryConsume]);

  // Wired to NavigationContainer's onReady: navigationRef.isReady() can still be false on the
  // first render where `ready` flips true.
  return { onNavigationReady: tryConsume };
}
