import { useState } from 'react';
import { authApi, type UserSettings } from '../../api/endpoints';
import { clearSessionAndRedirect } from '../../api/client';
import { TERMS_VERSION_OUTDATED } from '../../api/errorCodes';
import { Button } from '../../design-system';
import { DeleteAccountModal } from '../DeleteAccountModal';
import { ConsentChoices } from './ConsentChoices';
import { ConsentNotice } from './ConsentNotice';

interface Props {
  profile: UserSettings;
  onSubmit: (body: { acceptTermsVersion?: string; aiProcessing: boolean }) => Promise<void>;
  /** The documents changed while this was open: reload the profile (and so the version). */
  onOutdated: () => Promise<unknown>;
  onSignOut: () => void;
}

/**
 * The full-screen consent step (V302): the notice, the required box when this account has not
 * accepted the current Terms version, and the AI box. Nothing else in the app is reachable until it
 * is answered. Ways out: read the documents (links open a new tab), sign out, or delete the account.
 */
export function ConsentStep({ profile, onSubmit, onOutdated, onSignOut }: Props) {
  const needsTerms = profile.termsAcceptanceRequired;
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [aiEnabled, setAiEnabled] = useState(profile.aiProcessingConsent === true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const canContinue = (!needsTerms || termsAccepted) && !saving;

  async function submit() {
    if (!canContinue) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit({
        ...(needsTerms ? { acceptTermsVersion: profile.currentTermsVersion } : {}),
        aiProcessing: aiEnabled,
      });
    } catch (err: any) {
      if (err?.response?.data?.errorCode === TERMS_VERSION_OUTDATED) {
        setTermsAccepted(false);
        setError('The Terms of Service or Privacy Policy changed just now. Please read them again before you agree.');
        await onOutdated().catch(() => {});
      } else {
        setError('Something went wrong. Check your connection and try again.');
      }
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col items-center min-h-screen px-6 py-10 bg-bg">
      <div className="w-full max-w-lg">
        <h1 className="text-2xl font-bold text-ink mb-2 text-center">
          {needsTerms ? 'Before you continue' : 'One choice about AI features'}
        </h1>
        <p className="text-muted mb-6 text-center text-sm">
          {needsTerms
            ? 'Please read how Fynora uses your data, then confirm below.'
            : 'Fynora now asks before using AI features. They stay off unless you tick the box.'}
        </p>
        <div className="border border-border rounded-lg p-4 mb-6 bg-card max-h-[50vh] overflow-y-auto">
          <ConsentNotice />
        </div>
        <ConsentChoices
          idPrefix="consent-step"
          showTermsBox={needsTerms}
          termsAccepted={termsAccepted}
          onTermsAcceptedChange={setTermsAccepted}
          aiEnabled={aiEnabled}
          onAiEnabledChange={setAiEnabled}
        />
        {error && <p role="alert" className="text-danger text-sm mt-4">{error}</p>}
        <div className="mt-6 flex justify-center">
          <Button variant="primary" onClick={() => void submit()} disabled={!canContinue}>
            {saving ? 'Saving…' : 'Continue'}
          </Button>
        </div>
        <div className="mt-6 flex justify-center gap-6 text-xs">
          <button type="button" onClick={onSignOut} className="text-muted hover:text-ink font-medium underline">
            Sign out
          </button>
          <button type="button" onClick={() => setDeleteOpen(true)} className="text-muted hover:text-danger font-medium underline">
            Delete my account
          </button>
        </div>
      </div>
      {deleteOpen && (
        <DeleteAccountModal
          onClose={() => setDeleteOpen(false)}
          onDeleted={() => {
            authApi.logout().catch(() => {});
            clearSessionAndRedirect("Your account has been permanently deleted. You've been signed out everywhere.");
          }}
          signInMethod={profile.signInMethod}
        />
      )}
    </div>
  );
}
