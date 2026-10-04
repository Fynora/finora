import { useCallback, useEffect, useState } from 'react';
import { onboardingApi } from '../api/endpoints';

/** What was looked up, and for which account -- an answer for one account never stands for another. */
interface Lookup {
  key: string | null;
  answered: boolean;
}

/**
 * Whether the required "How do you keep track of your spending today?" question still needs an
 * answer, for a verified account -- new or one that finished onboarding before the question
 * existed. Read once per signed-in account; RootNavigator shows only the question while
 * {@code needsAnswer} is true.
 *
 * {@code pending} is true while the answer is being looked up. RootNavigator still draws its usual
 * screens then, so someone who has answered never waits on this, but keeps deep links from
 * navigating into the app until it is known whether the question replaces it.
 *
 * Plain state, not React Query: the query cache here is persisted to disk, and this must be
 * re-read for each account, never served from another session's copy. If the status cannot be
 * loaded, nothing is asked: a network failure never locks anyone out of their own money. The
 * backend still refuses to finish onboarding without the answer, and the question comes back on
 * the next launch.
 *
 * @param enabled  true once signed in and phone-verified
 * @param userKey  identifies the signed-in account, so a different account is asked afresh
 */
export function useSpendingQuestion(enabled: boolean, userKey: string | null) {
  const [lookup, setLookup] = useState<Lookup | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    onboardingApi.status()
      .then((status) => {
        if (!cancelled) setLookup({ key: userKey, answered: !!status.spendingTrackingMethod });
      })
      .catch(() => {
        // Not locked out -- see above.
        if (!cancelled) setLookup({ key: userKey, answered: true });
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, userKey]);

  const submit = useCallback(async (method: string) => {
    await onboardingApi.setSpendingTracking(method);
    setLookup({ key: userKey, answered: true });
  }, [userKey]);

  // Only a lookup made for this account counts; until one arrives, it is pending.
  const current = enabled && lookup !== null && lookup.key === userKey ? lookup : null;
  return {
    needsAnswer: current !== null && !current.answered,
    pending: enabled && current === null,
    submit,
  };
}
