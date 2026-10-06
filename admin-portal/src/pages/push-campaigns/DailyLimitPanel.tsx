import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { adminPushCampaignApi } from '../../api/endpoints';
import { formatIst } from '../../lib/istTime';
import { apiMessage, pushesPerDay } from './labels';

const SETTINGS_KEY = ['push-campaign-settings'];

/** The current daily limit per person; undefined until it has loaded. Shared by the panel and the editor. */
export function useDailyLimit(): number | undefined {
  const settings = useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => adminPushCampaignApi.getSettings(),
  });
  return settings.data?.dailyLimitPerPerson;
}

/**
 * The admin decides how many campaign pushes one person may get per day, across all campaigns.
 *
 * The number is the server's: it is read from there, changed there, and the allowed range comes
 * from there too, so this never guesses. Raising it asks for a confirmation (it can only make people
 * hear from Fynora more often); lowering it does not. A change applies from the next page any send
 * queues and never takes back a push someone already has -- the panel says so, because that is the
 * first question after pressing Save.
 */
export function DailyLimitPanel() {
  const queryClient = useQueryClient();
  const [choice, setChoice] = useState<number | null>(null);
  const [confirmRaise, setConfirmRaise] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const settings = useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => adminPushCampaignApi.getSettings(),
  });

  const save = useMutation({
    mutationFn: (limit: number) => adminPushCampaignApi.updateSettings(limit),
    onSuccess: (saved) => {
      setNotice(`Saved. From the next send, nobody gets more than ${pushesPerDay(saved.dailyLimitPerPerson)}.`);
      setError(null);
      setChoice(null);
      setConfirmRaise(false);
      void queryClient.invalidateQueries({ queryKey: SETTINGS_KEY });
    },
    onError: (err) => {
      setNotice(null);
      setError(apiMessage(err, 'Could not save the limit.'));
      setConfirmRaise(false);
    },
  });

  if (settings.isLoading) return <p className="text-muted text-sm" role="status">Loading the daily limit…</p>;
  if (settings.isError || !settings.data) {
    return (
      <p className="text-sm text-danger" role="alert">
        {apiMessage(settings.error, 'Could not load the daily limit.')}
      </p>
    );
  }

  const current = settings.data.dailyLimitPerPerson;
  const selected = choice ?? current;
  const changed = selected !== current;
  const options = Array.from(
    { length: settings.data.maxDailyLimit - settings.data.minDailyLimit + 1 },
    (_, i) => settings.data!.minDailyLimit + i,
  );

  function submit() {
    if (!changed) return;
    if (selected > current) {
      setConfirmRaise(true);
      return;
    }
    save.mutate(selected);
  }

  return (
    <div className="bg-card border border-border rounded-xl2 p-5 space-y-3">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="push-daily-limit" className="text-xs font-medium text-muted mb-1 block">
            Most campaign pushes one person gets in a day
          </label>
          <select
            id="push-daily-limit"
            className="bg-bg border border-border rounded-lg px-3 py-2 text-sm"
            value={selected}
            onChange={(e) => {
              setChoice(Number(e.target.value));
              setNotice(null);
              setError(null);
            }}
          >
            {options.map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </div>
        <button
          type="button"
          onClick={submit}
          disabled={!changed || save.isPending}
          className="text-sm font-semibold rounded-lg px-4 py-2 bg-primary hover:bg-primary-dark text-on-primary disabled:opacity-40"
        >
          {save.isPending ? 'Saving…' : 'Save limit'}
        </button>
      </div>

      <p className="text-xs text-muted max-w-3xl">
        Applies to all campaigns together, counted per day in IST. It takes effect from the next send and never
        takes back a push someone already got. The more pushes a day, the likelier people are to switch
        notifications off, and that also stops their bill and due-date warnings.
      </p>
      {settings.data.updatedBy && (
        <p className="text-xs text-muted">Last changed {formatIst(settings.data.updatedAt)}.</p>
      )}

      {notice && <p className="text-sm text-success bg-success-bg rounded-lg px-3 py-2" role="status">{notice}</p>}
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2" role="alert">{error}</p>}

      {confirmRaise && (
        <ConfirmDialog
          title="Allow more pushes a day?"
          message={`People can then get up to ${pushesPerDay(selected)} (it is ${current} now). Only raise it if you need to: anyone who switches notifications off also stops getting bill warnings.`}
          confirmLabel="Raise the limit"
          busy={save.isPending}
          onConfirm={() => save.mutate(selected)}
          onCancel={() => setConfirmRaise(false)}
        />
      )}
    </div>
  );
}
