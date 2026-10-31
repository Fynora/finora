import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { userApi } from '../../api/endpoints';
import { useAuth } from '../../context/AuthContext';
import { ConfirmDialog } from '../../design-system/ConfirmDialog';
import { consentQueryKey } from '../../components/consent/ConsentGate';

/**
 * Settings -> Data -> AI features (privacy workstream 7): the one switch for Ask Fyn, AI insights
 * and AI category suggestions. Turning it off is withdrawal: the backend stops every AI call and
 * deletes the AI insight texts written so far, so the switch asks first. Shares ConsentGate's
 * profile query, so the two never disagree.
 */
export function AiFeaturesSection() {
  const { email } = useAuth();
  const queryClient = useQueryClient();
  const queryKey = consentQueryKey(email);
  const profile = useQuery({ queryKey, queryFn: () => userApi.get(), staleTime: Infinity, retry: false });
  const [saving, setSaving] = useState(false);
  const [confirmOff, setConfirmOff] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const enabled = profile.data?.aiProcessingConsent === true;

  async function save(next: boolean) {
    setSaving(true);
    setError(null);
    try {
      const updated = await userApi.consent({ aiProcessing: next });
      queryClient.setQueryData(queryKey, updated);
      setConfirmOff(false);
    } catch {
      setError("Couldn't save that just now — please try again.");
      setConfirmOff(false);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="pt-4 mt-4 border-t border-border" data-testid="ai-features">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-ink font-medium text-sm">AI features</p>
          <p className="text-muted text-2xs mt-1">
            Ask Fyn, AI insights and AI category suggestions. When on, the question you ask, the account
            figures needed to answer it, or the description of a transaction you add by hand are sent to
            Anthropic (Claude), with names and account numbers Fynora recognises hidden first.
          </p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          aria-label="AI features"
          disabled={!profile.data || saving}
          onClick={() => (enabled ? setConfirmOff(true) : void save(true))}
          className={`w-11 h-6 rounded-full transition-colors relative flex-shrink-0 disabled:opacity-40 ${enabled ? 'bg-primary' : 'bg-border'}`}
        >
          <span className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow-card transition-transform ${enabled ? 'translate-x-5' : ''}`} />
        </button>
      </div>
      {profile.isError && <p className="text-xs text-warning mt-2">Couldn't load this setting just now.</p>}
      {error && <p role="alert" className="text-xs text-danger mt-2">{error}</p>}
      {confirmOff && (
        <ConfirmDialog
          title="Turn off AI features?"
          message="Fynora stops sending anything to the AI service. The AI insight texts written for you so far are deleted, and the details of past AI requests are cleared. Your Ask Fyn conversations stay. You can turn AI features back on at any time."
          confirmLabel="Turn off"
          danger
          busy={saving}
          onConfirm={() => void save(false)}
          onCancel={() => setConfirmOff(false)}
        />
      )}
    </div>
  );
}
