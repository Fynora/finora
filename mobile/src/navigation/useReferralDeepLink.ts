import { useCallback, useEffect, useRef } from 'react';
import { Linking } from 'react-native';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { createLaunchUrlGuard, parseAppLink } from '../lib/appLinks';
import type { RootParamList } from './types';

// See createLaunchUrlGuard: a remount (RootErrorBoundary "Try again") must not replay the launch link.
const isFirstLaunchDelivery = createLaunchUrlGuard();

export interface ReferralDeepLinkParams {
  referralCode: string;
}

/**
 * Parses "finora://register?ref=CODE" -- ReferralsScreen.tsx's share message includes this deep
 * link alongside the bare code -- and the equivalent Universal/App Link
 * ("https://app.fynora.net/register?ref=CODE", matching web's own /register route and its `ref`
 * query param). See lib/appLinks.ts for the shared URL handling and for how the OS is told to hand
 * the https form to the app when it's installed.
 *
 * A URL with no `ref` param (or no query string at all -- e.g. someone typed "finora://register"
 * by hand) returns null rather than a params object with an empty code: there is nothing this
 * hook needs to do differently from the plain "Register" footer link in that case, and prefilling
 * an empty string is not a real deep-link outcome worth stashing or replaying.
 */
export function parseReferralDeepLink(url: string): ReferralDeepLinkParams | null {
  const link = parseAppLink(url);
  if (!link || link.path !== '/register' || !link.params.ref) return null;
  return { referralCode: link.params.ref };
}

/**
 * Phase 5 (Low-Priority Polish). Register lives in AuthStack, one of RootNavigator's three
 * mutually-exclusive root trees -- mounted exactly when signed out, same as every other AuthStack
 * screen, so unlike useEmailChangeDeepLink this needs no separate "app fully ready" concept: the
 * gate this hook cares about is `authStackActive` (RootNavigator's `token === null`), which is
 * already resolved by the time NavigationContainer mounts at all (RootNavigator's own early
 * `bootstrapping` return blocks rendering until then).
 *
 * A referral code is only ever useful to someone about to register, so this deliberately does NOT
 * mirror useEmailChangeDeepLink's signedIn-vs-ready distinction for replaying a stashed link after
 * `authStackActive` flips back true: `handleUrl` only stashes a link that arrives while AuthStack
 * is ALREADY the active tree, so there is nothing left to (wrongly) replay after some later,
 * unrelated sign-out -- a link that arrived while signed in is simply dropped, not queued for
 * whoever happens to sign out next.
 */
export function useReferralDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  authStackActive: boolean,
) {
  const pendingRef = useRef<ReferralDeepLinkParams | null>(null);
  // Mirrors `authStackActive` into a ref for the same reason useEmailChangeDeepLink does: keeps
  // tryConsume referentially stable so its Linking-listener effect doesn't resubscribe on every
  // auth-state change.
  const activeRef = useRef(authStackActive);

  const tryConsume = useCallback(() => {
    if (!activeRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const params = pendingRef.current;
    if (!params) return;
    pendingRef.current = null;
    navigationRef.navigate('Register', { referralCode: params.referralCode });
  }, [navigationRef]);

  useEffect(() => {
    function handleUrl(url: string) {
      const parsed = parseReferralDeepLink(url);
      if (!parsed) return;
      // Not stashed at all when AuthStack isn't the active tree -- see this hook's own doc
      // comment on why a link arriving while signed in must not survive to a later sign-out.
      if (!activeRef.current) return;
      pendingRef.current = parsed;
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
    activeRef.current = authStackActive;
    if (!authStackActive) pendingRef.current = null;
    tryConsume();
  }, [authStackActive, tryConsume]);

  // Wired to NavigationContainer's own onReady prop, same reason as useEmailChangeDeepLink's own:
  // navigationRef.isReady() can still be false on the very first render where authStackActive
  // flips true.
  return { onNavigationReady: tryConsume };
}
