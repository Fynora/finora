import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { SpendingTrackingQuestion } from './SpendingTrackingQuestion';
import { PageLoading } from '../components/PageLoading';

/**
 * Shows the required "How do you keep track of your spending today?" question in place of
 * everything else -- onboarding or the app -- until it is answered, for a new account and for one
 * that finished onboarding before the question existed alike. Read once per session per account.
 *
 * While the status is loading, or if it fails to load, the page shows as usual: a returning user
 * who has answered never waits on this, and a network failure never locks anyone out of their own
 * money. The backend still refuses to finish onboarding without the answer (OnboardingService.
 * complete), and the question comes back on the next load.
 */
export function SpendingQuestionGate({ children, holdWhileLoading = false }: {
  children: ReactNode;
  /** Show the page loader, not the page, while the answer is looked up -- for someone who has not
   *  finished onboarding: on a slow network they could otherwise skip or finish onboarding before
   *  the question arrives, and finishing is refused until it is answered. */
  holdWhileLoading?: boolean;
}) {
  const { email, logout } = useAuth();
  const queryClient = useQueryClient();
  const queryKey = ['onboarding', 'status', email];
  const status = useQuery({
    queryKey,
    queryFn: () => onboardingApi.status(),
    staleTime: Infinity,
    retry: false,
  });

  if (status.data && !status.data.spendingTrackingMethod) {
    return (
      <SpendingTrackingQuestion
        onSubmit={async (method) => {
          const updated = await onboardingApi.setSpendingTracking(method);
          queryClient.setQueryData(queryKey, updated);
        }}
        // ProtectedRoute sends a signed-out session to /auth by itself.
        onSignOut={logout}
      />
    );
  }
  if (holdWhileLoading && status.isPending) {
    return <PageLoading />;
  }
  return <>{children}</>;
}
