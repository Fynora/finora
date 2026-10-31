import { useCallback, useEffect, useState } from 'react';
import { userApi, type UserSettings } from '../api/endpoints';

/** What was looked up, and for which account -- an answer for one account never stands for another. */
interface Lookup {
  key: string | null;
  /** Null when the profile could not be loaded: nothing is asked then (see below). */
  profile: UserSettings | null;
}

/** True when the consent step must be shown for this profile (V302). */
export function consentRequired(profile: UserSettings): boolean {
  return profile.termsAcceptanceRequired || profile.aiProcessingConsent === null;
}

/**
 * Whether the consent step (docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md)
 * must be shown: for an account that has not accepted the current Terms version, or has not
 * answered the AI question. RootNavigator shows only ConsentScreen while {@code needsAnswer} is true,
 * before the spending question, onboarding and the app.
 *
 * {@code pending} is true while the profile is being looked up; RootNavigator holds every screen
 * then, so the app never shows and then disappears behind the step.
 *
 * Plain state, not React Query, for useSpendingQuestion's reason: the query cache is persisted to
 * disk, and this must be read afresh for each signed-in account. If the profile cannot be loaded,
 * nothing is asked: a network failure never locks anyone out of their own money, and AI stays off
 * on the server until the user says yes. The step comes back on the next launch.
 *
 * @param enabled  true once signed in and phone-verified
 * @param userKey  identifies the signed-in account, so a different account is asked afresh
 */
export function useConsent(enabled: boolean, userKey: string | null) {
  const [lookup, setLookup] = useState<Lookup | null>(null);

  const load = useCallback(async () => {
    try {
      const profile = await userApi.get();
      setLookup({ key: userKey, profile });
    } catch {
      setLookup({ key: userKey, profile: null }); // not locked out -- see above
    }
  }, [userKey]);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    userApi.get()
      .then((profile) => { if (!cancelled) setLookup({ key: userKey, profile }); })
      .catch(() => { if (!cancelled) setLookup({ key: userKey, profile: null }); });
    return () => {
      cancelled = true;
    };
  }, [enabled, userKey]);

  const submit = useCallback(async (body: { acceptTermsVersion?: string; aiProcessing: boolean }) => {
    const profile = await userApi.consent(body);
    setLookup({ key: userKey, profile });
  }, [userKey]);

  // Only a lookup made for this account counts; until one arrives, it is pending.
  const current = enabled && lookup !== null && lookup.key === userKey ? lookup : null;
  return {
    needsAnswer: current?.profile != null && consentRequired(current.profile),
    pending: enabled && current === null,
    profile: current?.profile ?? null,
    submit,
    /** The documents changed while the step was open: read the profile (and its version) again. */
    reload: load,
  };
}
