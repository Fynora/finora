import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { userApi, type UserSettings } from '../../api/endpoints';
import { useAuth } from '../../context/AuthContext';
import { PageLoading } from '../PageLoading';
import { ConsentStep } from './ConsentStep';

/** Shared with the Settings "AI features" switch, so flipping it there keeps this gate in step. */
export function consentQueryKey(email: string | null) {
  return ['consent', email] as const;
}

/** True when the consent step must be shown for this profile (V302). */
export function consentRequired(profile: UserSettings): boolean {
  return profile.termsAcceptanceRequired || profile.aiProcessingConsent === null;
}

/**
 * Shows the consent step in place of everything else until it is answered
 * (docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md): for an account
 * that has not accepted the current Terms version, and for one that has not answered the AI
 * question. Comes first, before the spending question, onboarding and the app.
 *
 * The page waits while the profile loads, so the app never shows and then disappears behind the
 * step. If the profile fails to load, the app shows as usual: a network failure never locks anyone
 * out of their own money, and AI stays off on the server until the user says yes.
 */
export function ConsentGate({ children }: { children: ReactNode }) {
  const { email, logout } = useAuth();
  const queryClient = useQueryClient();
  const queryKey = consentQueryKey(email);
  const profile = useQuery({
    queryKey,
    queryFn: () => userApi.get(),
    staleTime: Infinity,
    retry: false,
  });

  if (profile.data && consentRequired(profile.data)) {
    return (
      <ConsentStep
        profile={profile.data}
        onSubmit={async (body) => {
          const updated = await userApi.consent(body);
          queryClient.setQueryData(queryKey, updated);
        }}
        onOutdated={() => profile.refetch()}
        // ProtectedRoute sends a signed-out session to /auth by itself.
        onSignOut={logout}
      />
    );
  }
  if (profile.isPending) return <PageLoading />;
  return <>{children}</>;
}
