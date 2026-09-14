import { useState } from 'react';
import { authApi } from '../../api/endpoints';
import { clearSessionAndRedirect } from '../../api/client';
import { DeactivateAccountModal } from '../../components/DeactivateAccountModal';
import { DeleteAccountModal } from '../../components/DeleteAccountModal';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';

export function AccountPane({
  loading, loadError, signInMethod,
}: {
  loading: boolean; loadError: boolean; signInMethod: 'PASSWORD' | 'GOOGLE';
}) {
  const [deactivateOpen, setDeactivateOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  // The account was just deactivated -- UserAccountLifecycleService.deactivate already revoked
  // every refresh token server-side, so there is nothing left to be signed in to. Never touches
  // AuthContext's React state (see clearSessionAndRedirect's own reasoning) -- ProtectedRoute
  // would otherwise race a client-side redirect against this hard navigation.
  function handleDeactivated() {
    authApi.logout().catch(() => {});
    clearSessionAndRedirect('Your account has been deactivated. Sign in again any time to reactivate it.');
  }

  function handleDeleted() {
    authApi.logout().catch(() => {});
    clearSessionAndRedirect("Your account has been permanently deleted. You've been signed out everywhere.");
  }

  return (
    <FinoraCard>
      <SectionHeader title="Manage Your Account" />
      <p className="text-sm text-muted -mt-3 mb-5">Deactivate or permanently delete your Fynora account</p>
      <div className="pt-1 pb-4 border-b border-border">
        <p className="text-ink font-medium text-sm">Deactivate Account</p>
        <p className="text-muted text-2xs mt-1 mb-3">
          Temporarily disable your account. You'll be signed out everywhere and won't be able to
          sign in until you reactivate -- your data is retained securely, and reactivating is as
          simple as signing in again.
        </p>
        <Button
          variant="secondary"
          size="sm"
          disabled={loading || loadError}
          title={loadError ? "Couldn't load your account details" : undefined}
          onClick={() => setDeactivateOpen(true)}
        >
          Deactivate Account
        </Button>
      </div>
      <div className="pt-4">
        <p className="text-ink font-medium text-sm">Delete Account</p>
        <p className="text-muted text-2xs mt-1 mb-3">
          Permanently delete your account and all your data. This cannot be undone, and there is
          no way to cancel this request once submitted.
        </p>
        <Button
          variant="danger"
          size="sm"
          disabled={loading || loadError}
          title={loadError ? "Couldn't load your account details" : undefined}
          onClick={() => setDeleteOpen(true)}
        >
          Delete Account
        </Button>
      </div>

      {deactivateOpen && (
        <DeactivateAccountModal
          onClose={() => setDeactivateOpen(false)}
          onDeactivated={handleDeactivated}
          signInMethod={signInMethod}
        />
      )}

      {deleteOpen && (
        <DeleteAccountModal
          onClose={() => setDeleteOpen(false)}
          onDeleted={handleDeleted}
          signInMethod={signInMethod}
        />
      )}
    </FinoraCard>
  );
}
